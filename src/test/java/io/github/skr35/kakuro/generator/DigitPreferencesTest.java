package io.github.skr35.kakuro.generator;

import io.github.skr35.kakuro.model.Board;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DigitPreferencesTest {
    @Test void lowAndHighDigitsHaveSymmetricCostsIncludingPartialAssignments() {
        boolean[] mask=new boolean[16];
        for(int r=1;r<4;r++)for(int c=1;c<4;c++)mask[r*4+c]=true;
        var runs=Board.geometry(4,mask);
        int[] values={0,0,0,0,0,1,2,3,0,2,3,4,0,3,4,5};
        for(int missing=0;missing<9;missing++) {
            int[] complement=values.clone();
            for(int i=0;i<complement.length;i++)if(complement[i]>0)complement[i]=10-complement[i];
            assertEquals(DigitPreferences.penalty(4,4,runs,values),DigitPreferences.penalty(4,4,runs,complement),1e-12);
            values[(missing/3+1)*4+missing%3+1]=0;
        }
    }
}
