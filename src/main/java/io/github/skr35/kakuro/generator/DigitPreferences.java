package io.github.skr35.kakuro.generator;

import io.github.skr35.kakuro.model.Run;
import java.util.List;

/** Soft, complement-symmetric preferences for partial or complete assignments; never a proof. */
final class DigitPreferences {
    private DigitPreferences() { }
    static double penalty(int rows,int cols,List<Run> runs,int[] values) {
        int[] counts=new int[9],pairs=new int[81],triples=new int[729];
        int[][] regions=new int[4][9];
        for(int c=0;c<values.length;c++)if(values[c]>0) {
            counts[values[c]-1]++;
            regions[(c/cols>rows/2?2:0)+(c%cols>cols/2?1:0)][values[c]-1]++;
        }
        double cost=imbalance(counts);
        for(int[] region:regions)cost+=.30*imbalance(region);
        for(Run run:runs)for(int i=1;i<run.cells().size();i++) {
            int a=values[run.cells().get(i-1)],b=values[run.cells().get(i)];
            if(a==0 || b==0)continue;
            cost+=.08*pairs[(a-1)*9+b-1]++;
            if(i<2)continue;
            int first=values[run.cells().get(i-2)];if(first==0)continue;
            cost+=.5*triples[(first-1)*81+(a-1)*9+b-1]++;
            if((a-first)*(b-a)>0)cost+=.10;
            if(a-first==b-a && Math.abs(a-first)==1)cost+=.35;
        }
        return cost;
    }
    private static double imbalance(int[] counts) {
        double n=0,squared=0;for(int count:counts){n+=count;squared+=count*count;}
        return n==0?0:squared/n-n/9;
    }
}
