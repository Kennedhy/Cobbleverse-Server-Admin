package br.com.cobbleverse.generations.rules;

/** Scales the final success probability while retaining the original critical-roll frequency. */
public final class CaptureRules {
    private static final double RANGE=65537.0;
    private CaptureRules() {}

    public static int threshold(int original,boolean critical,double criticalChance,double multiplier) {
        if(multiplier==1.0) return original;
        double shake=Math.max(0,Math.min(1,original/RANGE));
        double c=Math.max(0,Math.min(1,criticalChance));
        double normal=Math.pow(shake,4), rare=shake;
        double target=Math.min(1,(c*rare+(1-c)*normal)*multiplier);
        double newRare=Math.min(1,rare*multiplier), newNormal=Math.min(1,normal*multiplier);
        if(c*newRare+(1-c)*newNormal < target && c<1 && newRare>=1)
            newNormal=Math.min(1,Math.max(0,(target-c)/(1-c)));
        double newShake=critical ? newRare : Math.pow(newNormal,0.25);
        return Math.max(0,Math.min(65537,(int)Math.round(newShake*RANGE)));
    }
}
