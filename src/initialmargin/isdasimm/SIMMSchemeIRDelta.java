package initialmargin.isdasimm;

import net.finmath.montecarlo.RandomVariable;
import net.finmath.stochastic.RandomVariableInterface;

import java.util.Map;
import java.util.Optional;


public class SIMMSchemeIRDelta {
	
    SIMMSchemeMain calculationSchemeInitialMarginISDA;
    String      productClassKey;
    String      riskClassKey;
    String[]    bucketKeys;
    final String riskTypeKey = "delta";

    public SIMMSchemeIRDelta(SIMMSchemeMain calculationSchemeInitialMarginISDA,
                               String productClassKey){
        this.calculationSchemeInitialMarginISDA = calculationSchemeInitialMarginISDA;
        this.riskClassKey = "InterestRate";
        this.productClassKey = productClassKey;
        this.bucketKeys = new String[0];//calculationSchemeInitialMarginISDA.getMapRiskClassBucketKeys(riskTypeKey).get(riskClassKey);
    }

    public RandomVariableInterface getValue(double atTime){

        if (this.bucketKeys.length==0)
            return new RandomVariable(atTime,this.calculationSchemeInitialMarginISDA.getPathDimension(),0.0);

        RandomVariableInterface[] S1Contributions = new RandomVariableInterface[this.bucketKeys.length];
        RandomVariableInterface[] KContributions = new RandomVariableInterface[this.bucketKeys.length];
        int i=0;
        for (String bucketKey : this.bucketKeys)
        {
            RandomVariableInterface K1 = this.getAggregatedSensitivityForBucket(bucketKey,atTime);
            RandomVariableInterface S1 = this.getFactorS(bucketKey,K1,atTime);
            S1Contributions[i] = S1;
            KContributions[i] = K1;
            i++;
        }

        RandomVariableInterface deltaMargin = null;
        RandomVariableInterface VarCovar = null;
        Double[][] correlationMatrix = null;

        double singleCorrelation = calculationSchemeInitialMarginISDA.getParameterCollection().IRCorrelationCrossCurrency;
        correlationMatrix = new Double[this.bucketKeys.length][this.bucketKeys.length];
        for (i = 0; i< bucketKeys.length;i++)
        for (int j = 0; j< bucketKeys.length;j++)
            if ( i!=j)
                correlationMatrix[i][j] = getParameterG(bucketKeys[i],bucketKeys[j],atTime).getAverage()*singleCorrelation;
        VarCovar = SIMMSchemeMain.getVarianceCovarianceAggregation(S1Contributions, correlationMatrix);


        /*Adjustment on Diagonal*/
        VarCovar = VarCovar.squared();
        RandomVariableInterface SSumSQ = null;
        RandomVariableInterface KSumSQ = null;
        for ( int k = 0;k<S1Contributions.length;k++){
            SSumSQ = SSumSQ == null ? SSumSQ = S1Contributions[k].squared() : SSumSQ.add(S1Contributions[k].squared());
            KSumSQ = KSumSQ == null ? KSumSQ = KContributions[k].squared() : KSumSQ.add(KContributions[k].squared());
        }
        VarCovar = VarCovar.sub(SSumSQ).add(KSumSQ);
        deltaMargin = VarCovar.sqrt();

        if ( Double.isNaN(deltaMargin.get(0)))
            System.out.print("");
        return deltaMargin;
    }



    private RandomVariableInterface getAggregatedSensitivityForBucket(String bucketKey, double atTime){
        RandomVariableInterface aggregatedSensi = null;


        //if ( riskClassKey.equals("InterestRate")) {
        /**
         * Changed to first across curves with sub curve correlation
         * Then across tenors
         * therefore riskfactor is tenor,
         */

        int nTenors = calculationSchemeInitialMarginISDA.getParameterCollection().IRMaturityBuckets.length;
        int nCurves = calculationSchemeInitialMarginISDA.getParameterCollection().IRCurveIndexNames.length;

        int dimensionTotal=nTenors*nCurves+2;
        RandomVariableInterface[] contributions = new RandomVariableInterface[dimensionTotal];

        for (int iCurve = 0; iCurve <nCurves; iCurve++)
            for (int iTenor = 0; iTenor <nTenors; iTenor++)
            {
                String curveKey = calculationSchemeInitialMarginISDA.getParameterCollection().IRCurveIndexNames[iCurve];
                RandomVariableInterface iBucketSensi = this.getWeightedNetSensitivity(iTenor, curveKey, bucketKey, atTime);
                contributions[iCurve*nTenors+iTenor] = iBucketSensi;
            }

        RandomVariableInterface inflationSensi = this.getWeightedNetSensitivity(0,"inflation",bucketKey,atTime);
        RandomVariableInterface ccyBasisSensi = this.getWeightedNetSensitivity(0,"ccybasis",bucketKey,atTime);
        contributions[dimensionTotal-2] = inflationSensi;
        contributions[dimensionTotal-1] = ccyBasisSensi;

        Double[][] crossTenorCorrelation = calculationSchemeInitialMarginISDA.getParameterCollection().MapRiskClassCorrelationIntraBucketMap.get(riskClassKey);

        aggregatedSensi = SIMMSchemeMain.getVarianceCovarianceAggregation(contributions, crossTenorCorrelation);
        //}

        if ( Double.isNaN(aggregatedSensi.get(0)))
            System.out.print("");
        return aggregatedSensi;
    }



    private RandomVariableInterface   getWeightedNetSensitivity(int iRateTenor,String indexName,String bucketKey, double atTime)
    {
        double riskWeight = 0;


        if (!indexName.equals("inflation") && !indexName.equals("ccybasis"))
        {
            Optional<Map.Entry<String, String>> optional = calculationSchemeInitialMarginISDA.getParameterCollection().IRCurrencyMap.entrySet().stream().filter(entry -> entry.getKey().contains(bucketKey)).findAny();
            String currencyMapKey;
            if (!optional.isPresent())
                currencyMapKey = "High_Volatility_Currencies";
            else
                currencyMapKey = optional.get().getValue();
            currencyMapKey = currencyMapKey.replace("_Traded", "").replace("_Well", "").replace("_Less", "");
            Double[] riskWeights = calculationSchemeInitialMarginISDA.getParameterCollection().MapRiskClassRiskweightMap.get(riskTypeKey).get("InterestRate").get(currencyMapKey)[0];
            riskWeight = riskWeights[iRateTenor];
        }
        else {
            riskWeight = calculationSchemeInitialMarginISDA.getParameterCollection().MapRiskClassRiskweightMap.get(riskTypeKey).get("InterestRate").get(indexName)[0][0];
        }

        RandomVariableInterface netSensi =  calculationSchemeInitialMarginISDA.getNetSensitivity(this.productClassKey,this.riskClassKey,iRateTenor, indexName, bucketKey,this.riskTypeKey, atTime);
        if (netSensi!=null) {
            if ( indexName.equals("ccybasis"))
                return netSensi.mult(riskWeight);
            else {
                RandomVariableInterface concentrationRiskFactor = this.getConcentrationRiskFactor(bucketKey,atTime);
                return netSensi.mult(riskWeight).mult(concentrationRiskFactor);
            }
        }
        else
            return null;
    }

    public RandomVariableInterface     getParameterG(String bucketKey1, String bucketKey2, double atTime){
        RandomVariableInterface CR1 = this.getConcentrationRiskFactor(bucketKey1,atTime);
        RandomVariableInterface CR2 = this.getConcentrationRiskFactor(bucketKey2,atTime);
        RandomVariableInterface min = CR1.barrier(CR1.sub(CR2),CR2,CR1);
        RandomVariableInterface max = CR1.barrier(CR1.sub(CR2),CR1,CR2);
        return min.div(max);

    }



    public RandomVariableInterface getFactorS(String bucketKey, RandomVariableInterface K, double atTime){
        RandomVariableInterface sum = this.getWeightedSensitivitySum(bucketKey, atTime);
        RandomVariableInterface S1 = K.barrier(sum.sub(K),K,sum);
        RandomVariableInterface KNegative = K.mult(-1);
        S1 = S1.barrier(S1.sub(KNegative),S1,KNegative);
        return S1;
    }

    private RandomVariableInterface   getWeightedSensitivitySum(String bucketKey, double atTime){
        RandomVariableInterface aggregatedSensi = null;

        for (int iIndex = 0; iIndex < calculationSchemeInitialMarginISDA.getParameterCollection().IRCurveIndexNames.length; iIndex++) {
            for (int iTenor = 0; iTenor < calculationSchemeInitialMarginISDA.getParameterCollection().IRMaturityBuckets.length; iTenor++) {
                String key = calculationSchemeInitialMarginISDA.getParameterCollection().IRCurveIndexNames[iIndex];
                RandomVariableInterface summand = getWeightedNetSensitivity(iTenor, key, bucketKey, atTime);
                aggregatedSensi = aggregatedSensi == null ? aggregatedSensi = summand : aggregatedSensi.add(summand);
            }
        }
        RandomVariableInterface inflationSensi = this.getWeightedNetSensitivity(0,"inflation",bucketKey,atTime);
        RandomVariableInterface ccyBasisSensi = this.getWeightedNetSensitivity(0,"ccybasis",bucketKey,atTime);
        return aggregatedSensi.add(inflationSensi.add(ccyBasisSensi));


    }


    public RandomVariableInterface getConcentrationRiskFactor(String bucketKey, double atTime){
        RandomVariableInterface sensitivitySum = null;
        for (int iIndex = 0; iIndex < calculationSchemeInitialMarginISDA.getParameterCollection().IRCurveIndexNames.length; iIndex++) {
            for (int iTenor = 0; iTenor < calculationSchemeInitialMarginISDA.getParameterCollection().IRMaturityBuckets.length; iTenor++) {
                String key = calculationSchemeInitialMarginISDA.getParameterCollection().IRCurveIndexNames[iIndex];
                RandomVariableInterface summand = calculationSchemeInitialMarginISDA.getNetSensitivity(this.productClassKey,this.riskClassKey,iTenor,key,bucketKey,"delta",atTime);//"ccybasis",bucketKey,"delta",atTime);//getWeightedNetSensitivity(iTenor, key, bucketKey, atTime);
                sensitivitySum = sensitivitySum == null ? sensitivitySum = summand : sensitivitySum.add(summand);
            }
        }
        RandomVariableInterface inflationSensi = calculationSchemeInitialMarginISDA.getNetSensitivity(this.productClassKey,this.riskClassKey,0,"inflation",bucketKey,"delta",atTime);
        sensitivitySum = sensitivitySum.add(inflationSensi); // Inflation Sensi are included in Sum, CCYBasis not

        Optional<Map.Entry<String,String> > optional = calculationSchemeInitialMarginISDA.getParameterCollection().IRCurrencyMap.entrySet().stream().filter(entry->entry.getKey().contains(bucketKey)).findAny();
        String currencyMapKey;
        if (!optional.isPresent())
            currencyMapKey="High_Volatility_Currencies";
        else
            currencyMapKey = optional.get().getValue();

        double concentrationThreshold = calculationSchemeInitialMarginISDA.getParameterCollection().MapRiskClassThresholdMap.get(this.riskTypeKey).get(riskClassKey).get(currencyMapKey)[0][0];
        RandomVariableInterface CR = (sensitivitySum.abs().div(concentrationThreshold)).sqrt();
        CR = CR.barrier(CR.sub(1.0), CR, 1.0);
        return CR;
    }



}