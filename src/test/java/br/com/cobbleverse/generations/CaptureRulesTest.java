package br.com.cobbleverse.generations;

import br.com.cobbleverse.generations.rules.CaptureRules;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CaptureRulesTest {
    @Test void identityPreservesEveryThreshold() {
        for(int t=0;t<=65537;t+=127) for(boolean critical:new boolean[]{false,true})
            assertEquals(t,CaptureRules.threshold(t,critical,.4,1));
    }
    @Test void scalesWeightedSuccessProbabilityIncludingSaturation() {
        for(int t:new int[]{0,1,3000,18000,32768,50000,65536,65537})
            for(double c:new double[]{0,.01,.25,.75,1})
                for(double m:new double[]{0,.1,.5,2,10,100}) {
                    double s=t/65537.0;
                    double expected=Math.min(1,(c*s+(1-c)*Math.pow(s,4))*m);
                    double critical=CaptureRules.threshold(t,true,c,m)/65537.0;
                    double normal=CaptureRules.threshold(t,false,c,m)/65537.0;
                    assertEquals(expected,c*critical+(1-c)*Math.pow(normal,4),0.00004,
                        "threshold="+t+" critical="+c+" multiplier="+m);
                }
    }
}
