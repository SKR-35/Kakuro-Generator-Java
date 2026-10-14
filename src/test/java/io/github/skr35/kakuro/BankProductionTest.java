package io.github.skr35.kakuro;

import io.github.skr35.kakuro.batch.BatchGenerator;
import io.github.skr35.kakuro.batch.BoundedProcess;
import io.github.skr35.kakuro.cli.KakuroCommand;
import io.github.skr35.kakuro.generator.Difficulty;
import io.github.skr35.kakuro.generator.Generator;
import io.github.skr35.kakuro.geometry.GeometryBuilder;
import io.github.skr35.kakuro.geometry.GeometryQualityEvaluator;
import io.github.skr35.kakuro.geometry.GeometryDiversity;
import io.github.skr35.kakuro.model.Board;
import io.github.skr35.kakuro.model.Puzzle;
import io.github.skr35.kakuro.persistence.BankStore;
import io.github.skr35.kakuro.persistence.PuzzleRepository;
import io.github.skr35.kakuro.solver.Solver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BankProductionTest {
    @TempDir Path temp;
    private record Execution(int code,String out,String err) { }
    private Execution execute(String... args) {
        var out=new StringWriter(); var err=new StringWriter();
        int code=new CommandLine(new KakuroCommand()).setOut(new PrintWriter(out)).setErr(new PrintWriter(err)).execute(args);
        return new Execution(code,out.toString(),err.toString());
    }
    private static Generator.Statistics stats() { return new Generator.Statistics(1,1,1,1,1,1,1,1,1,1,0,0,0,0); }
    private static BatchGenerator.Outcome accepted(Puzzle puzzle) {
        var task=new BatchGenerator.Task(1,puzzle.difficulty(),puzzle.board().rows(),puzzle.board().cols(),puzzle.seed(),1);
        return new BatchGenerator.Outcome(task,new Generator.Generated(puzzle,1,stats()),.01,null,stats());
    }
    private static Puzzle squareFixture() throws Exception {
        try(var reader=new BufferedReader(new InputStreamReader(Objects.requireNonNull(BankProductionTest.class.getResourceAsStream("/logical-fixtures.csv"))))) {
            reader.readLine(); var fields=reader.readLine().split(",");
            int[] digits=fields[3].chars().map(c->c-'0').toArray(); boolean[] white=new boolean[digits.length];
            for(int i=0;i<digits.length;i++)white[i]=digits[i]!=0;
            Board board=Board.fromSolution(8,8,white,digits); var proof=new Solver().solve(board,20000);
            return new Puzzle(board,digits,Difficulty.EASY,Difficulty.score(board,proof.nodes()),2,proof.nodes());
        }
    }

    @Test void durableAcceptedDuplicateAndExhaustedSlotsPreserveSchemaAndHashes() throws Exception {
        Path db=temp.resolve("bank.db"); var puzzle=Fixtures.unique(); var bucket=new BankStore.Bucket(3,3,Difficulty.EASY);
        try(var bank=new BankStore(db)) {
            assertEquals(new BankStore.Config(42,1),bank.configure(42L,1));
            assertEquals("ACCEPTED",bank.complete(bucket,0,accepted(puzzle)));
            assertEquals("DUPLICATE",bank.complete(bucket,1,accepted(puzzle)));
            var failed=new BatchGenerator.Outcome(accepted(puzzle).task(),null,.1,"exhausted",stats());
            assertEquals("EXHAUSTED",bank.complete(bucket,2,failed));
            assertEquals(3,bank.nextSlot(bucket)); assertEquals(1,bank.repository().all().size());
            assertTrue(bank.totals().contains("accepted=1 duplicates=1 exhausted=1"));
        }
        try(var bank=new BankStore(db)) {
            assertEquals(new BankStore.Config(42,1),bank.configure(null,null)); assertEquals(3,bank.nextSlot(bucket));
            assertThrows(IllegalArgumentException.class,()->bank.configure(43L,null));
            assertThrows(IllegalArgumentException.class,()->bank.configure(null,2));
            assertEquals(puzzle.fingerprint(),bank.repository().find(1).orElseThrow().puzzle().fingerprint());
        }
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var s=c.createStatement();var r=s.executeQuery("PRAGMA user_version")) { assertTrue(r.next());assertEquals(2,r.getInt(1)); }
    }

    @Test void checkpointFailureRollsBackPuzzleAndCursorTogether() throws Exception {
        Path db=temp.resolve("atomic.db"); var p=Fixtures.unique();
        try(var bank=new BankStore(db)) {
            bank.configure(9L,1); var bucket=new BankStore.Bucket(3,3,Difficulty.EASY);
            assertThrows(SQLException.class,()->bank.complete(bucket,1,accepted(p)));
            assertEquals(0,bank.nextSlot(bucket)); assertTrue(bank.repository().all().isEmpty());
            assertThrows(SQLException.class,()->bank.repository().saveCheckpoint(p,insert -> { throw new SQLException("Simulated checkpoint failure"); }));
            assertTrue(bank.repository().all().isEmpty());
            assertThrows(AssertionError.class,()->bank.repository().saveCheckpoint(p,insert -> { throw new AssertionError("Simulated abrupt checkpoint failure"); }));
            assertTrue(bank.repository().all().isEmpty());
            assertEquals("ACCEPTED",bank.complete(bucket,0,accepted(p)));
            assertEquals(1,bank.nextSlot(bucket));
        }
    }

    @Test void concurrentBankWriterIsRejectedAndLockIsReleased() throws Exception {
        Path db=temp.resolve("locked.db");
        try(var first=new BankStore(db)) { assertThrows(IllegalStateException.class,()->new BankStore(db)); }
        try(var next=new BankStore(db)) { assertNotNull(next.repository()); }
    }

    @Test void fixedStreamsAreBucketScopedAndIndependentOfTargetAndWorkerSettings() {
        var bucket=new BankStore.Bucket(8,8,Difficulty.EASY); Set<Long> seeds=new HashSet<>();
        for(long slot=0;slot<100;slot++) {
            long seed=BankStore.seed(123,bucket,slot); assertEquals(seed,BankStore.seed(123,bucket,slot)); assertTrue(seeds.add(seed));
            assertNotEquals(seed,BankStore.seed(123,new BankStore.Bucket(8,8,Difficulty.EVIL),slot));
            assertNotEquals(seed,BankStore.seed(123,new BankStore.Bucket(10,8,Difficulty.EASY),slot));
        }
    }

    @Test void resumeAndWorkerChangesProduceSameCommittedStream() throws Exception {
        Path continuous=temp.resolve("continuous.db"), resumed=temp.resolve("resumed.db");
        assertEquals(1,execute("bank","--db",continuous.toString(),"--target","8x8:EVIL:50","--seed","73","--max-attempts","1","--max-slots","3","--workers","2").code());
        assertEquals(1,execute("bank","--db",resumed.toString(),"--target","8x8:EVIL:50","--seed","73","--max-attempts","1","--max-slots","1").code());
        assertEquals(1,execute("bank","--db",resumed.toString(),"--target","8x8:EVIL:50","--max-slots","2","--workers","3").code());
        assertEquals(events(continuous),events(resumed));
        try(var a=new PuzzleRepository(continuous);var b=new PuzzleRepository(resumed)) {
            assertEquals(a.all().stream().map(p->p.puzzle().fingerprint()).toList(),b.all().stream().map(p->p.puzzle().fingerprint()).toList());
        }
    }
    private List<String> events(Path db) throws Exception {
        List<String> rows=new ArrayList<>();
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var s=c.createStatement();var r=s.executeQuery("SELECT bucket,slot,seed,status,candidates,details FROM bank_events ORDER BY bucket,slot")) {
            while(r.next())rows.add(r.getString(1)+r.getLong(2)+r.getLong(3)+r.getString(4)+r.getInt(5)+r.getString(6));
        }
        assertEquals(3,rows.size()); return rows;
    }

    @Test void existingPuzzlesSatisfyTargetsWithoutNewGenerationOrChangedLabels() throws Exception {
        Path db=temp.resolve("existing.db"); Puzzle p=squareFixture();
        try(var repository=new PuzzleRepository(db)) { repository.save(p); }
        var result=execute("bank","--db",db.toString(),"--target","8x8:EASY:1","--seed","5","--max-attempts","1");
        assertEquals(0,result.code(),result.err()); assertTrue(result.out().contains("committed-slots=0"));
        assertEquals(0,execute("bank","--db",db.toString(),"--target","8x8:EASY:1").code());
        var report=execute("bank-report","--db",db.toString(),"--logical");
        assertEquals(0,report.code(),report.err()); assertTrue(report.out().contains("logical-v1,SOLVED"));
        assertEquals(1,execute("bank-report","--db",db.toString(),"--logical","--analysis-budget","1").code());
        try(var repository=new PuzzleRepository(db)) {
            assertEquals(p.score(),repository.find(1).orElseThrow().puzzle().score());
            assertEquals(1,repository.count(8,8,Difficulty.EASY));
            assertEquals(1,new IndependentSolver(repository.find(1).orElseThrow().puzzle().board()).solve().size());
        }
    }

    @Test void pagedScanExcludesRecordsInsertedAfterItsHighWaterMark() throws Exception {
        try(var repository=new PuzzleRepository(temp.resolve("pages.db"))) {
            repository.save(Fixtures.unique()); var scan=repository.scan(); repository.save(squareFixture());
            var rows=new ArrayList<PuzzleRepository.Saved>(); scan.forEach(rows::add); assertEquals(1,rows.size());
            assertEquals(1,repository.page(0,1).size()); assertEquals(1,repository.page(1,1).size());
            assertThrows(IllegalArgumentException.class,()->repository.page(0,0));
        }
    }

    @Test void rejectsAmbiguousOrUnboundedProductionOptions() {
        String db=temp.resolve("invalid.db").toString();
        for(String[] options:List.of(new String[]{"--sizes","8"},new String[]{"--each","50"},
                new String[]{"--target","8x8:EASY:1","--target","8x8:EASY:2"},
                new String[]{"--sizes","8","--each","50","--target","8x8:EASY:1"},
                new String[]{"--target","15x30:EVIL:1"},new String[]{"--sizes","8","--each","1","--workers","0"},
                new String[]{"--sizes","8","--each","1","--max-slots","0"})) {
            var args=new ArrayList<>(List.of("bank","--db",db,"--seed","1"));args.addAll(List.of(options));
            assertEquals(2,execute(args.toArray(String[]::new)).code());
        }
        assertEquals(2,execute("bank","--db",db,"--sizes","8","--each","0").code());
        assertEquals(0,execute("bank","--db",db,"--sizes","8,10,12,15","--each","0","--seed","1").code());
        assertEquals(2,execute("--easy","1","bank","--db",db,"--sizes","8","--each","0").code());
    }

    @Test void wideExperimentsAreOptInAndKeepGeometryValidation() {
        for(int cols:new int[]{30,40,50}) {
            assertTrue(GeometryBuilder.supported(15,cols)); assertTrue(GeometryBuilder.supported(cols,15));
            assertThrows(IllegalArgumentException.class,()->new Generator.Session(Difficulty.MEDIUM,15,cols,1,1));
            assertDoesNotThrow(()->new Generator.Session(Difficulty.MEDIUM,15,cols,1,1,true));
            var result=new GeometryQualityEvaluator().evaluate(15,cols,new boolean[15*cols]);
            assertFalse(result.accepted());
        }
        assertFalse(GeometryBuilder.supported(15,31));
        assertEquals(2,execute("--rows","15","--cols","30","--no-pdf").code());
        assertEquals(2,execute("probe","--cols","30","--difficulty","MEDIUM","--seed","1","--max-attempts","1","--seconds","0","--outdir",temp.resolve("probe").toString()).code());
    }

    @Test void sameSizeDiversityOptimizationMatchesExpandedSamplingExactly() {
        for(int[] dimensions:List.of(new int[]{8,8},new int[]{8,12})) {
            int rows=dimensions[0],cols=dimensions[1],nr=rows-1,nc=cols-1;
            var boards=new ArrayList<Board>();
            for(int seed=0;seed<2;seed++) {
                boolean[] mask=Generator.geometry(rows,cols,new Random(seed),Difficulty.MEDIUM);
                int[] sums=Board.geometry(rows,cols,mask).stream().mapToInt(r->r.cells().size()*(r.cells().size()+1)/2).toArray();
                boards.add(new Board(rows,cols,mask,sums));
            }
            double expected=1; boolean square=rows==cols;
            for(int transform=0;transform<(square?8:4);transform++) {
                int differences=0;
                for(int r=0;r<nr*nr;r++)for(int c=0;c<nc*nc;c++) {
                    int br=r/nr,bc=c/nc;
                    if(square) {
                        if(transform>=4)bc=nc-1-bc;
                        for(int t=0;t<transform%4;t++){int old=br;br=bc;bc=nc-1-old;}
                    } else {if((transform&1)!=0)br=nr-1-br;if((transform&2)!=0)bc=nc-1-bc;}
                    if(boards.get(0).isWhite((r/nr+1)*cols+c/nc+1)!=boards.get(1).isWhite((br+1)*cols+bc+1))differences++;
                }
                expected=Math.min(expected,differences/(double)(nr*nr*nc*nc));
            }
            assertEquals(expected,GeometryDiversity.distance(boards.get(0),boards.get(1)));
        }
    }

    /** Test process only: no wide-board generation or stress work. */
    public static final class SleepingWorker { public static void main(String[] args) throws Exception { Thread.sleep(60_000); } }
    @Test void processDeadlineTerminatesWorkerWithoutCallingGenerator() throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var result=BoundedProcess.run(List.of(java,"-cp",System.getProperty("java.class.path"),SleepingWorker.class.getName()),1000,temp.resolve("timeout.log"));
        assertTrue(result.timedOut()); assertTrue(result.seconds()<10); assertNotEquals(0,result.exitCode());
    }
}
