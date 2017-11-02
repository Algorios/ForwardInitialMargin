package initialmargin.isdasimm;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.SingularValueDecomposition;

import initialmargin.isdasimm.changedfinmath.LIBORModelMonteCarloSimulationInterface;
import initialmargin.isdasimm.changedfinmath.products.AbstractLIBORMonteCarloProduct;
import initialmargin.isdasimm.changedfinmath.products.BermudanSwaption;
import initialmargin.isdasimm.changedfinmath.products.SimpleSwap;
import initialmargin.isdasimm.changedfinmath.products.Swaption;
import net.finmath.analytic.model.curves.DiscountCurve;
import net.finmath.exception.CalculationException;
import net.finmath.montecarlo.RandomVariable;
import net.finmath.montecarlo.automaticdifferentiation.RandomVariableDifferentiableInterface;
import net.finmath.montecarlo.conditionalexpectation.MonteCarloConditionalExpectationRegression;
//import net.finmath.montecarlo.interestrate.LIBORModelMonteCarloSimulationInterface;
//import net.finmath.montecarlo.interestrate.LIBORModelMonteCarloSimulation;
//import net.finmath.montecarlo.interestrate.LIBORModelMonteCarloSimulationInterface;
import net.finmath.optimizer.SolverException;
import net.finmath.stochastic.ConditionalExpectationEstimatorInterface;
import net.finmath.stochastic.RandomVariableInterface;
import net.finmath.time.TimeDiscretization;

/**
 * 
 * @author Mario Viehmann
 *
 */
public class SIMMPortfolio extends AbstractLIBORMonteCarloProduct{
	
	
	private PortfolioInstrument[] portfolioProducts;
    private RandomVariableInterface[][] riskWeightToLiborAdjustments;
    private boolean isUseTimeGridAdjustment=true; // can be discarded later.. just to check how discount curve influences IM
    private ConditionalExpectationEstimatorInterface conditionalExpectationOperator;// conditionalExpectationOperator;
    
    public enum WeightToLiborAdjustmentMethod{
		Constant,  //Sets dL/dS(t=0) for all forward IM times, i.e. leave the weight adjustment dL/dS constant
		Stochastic //Calculate dL/dS(t) for all forward IM times, i.e. (weakly) stochastic weight adjustment 
	}
    
    private WeightToLiborAdjustmentMethod liborWeightMethod;
    private LIBORModelMonteCarloSimulationInterface model = null;
    private SIMMSchemeMain SIMMScheme;
    private String calculationCCY; 
    
    public enum SensitivityMode{
    	LinearMelting,
    	LinearMeltingOnLiborBuckets,
    	Interpolation,
    	Stochastic
    }
    
    private SensitivityMode sensitivityMode;
    private double sensiResetStep;
    
	public class PortfolioInstrument {
		private SIMMClassifiedProduct classifiedProduct;
	    private Map<Long, RandomVariableInterface> gradientOfProduct = null; // Same for all evaluationTimes; Is reset for different products
	    private double lastEvaluationTime = Double.MIN_VALUE;
	    private SensitivityInterpolation sensitivityInterpolation;
	    public SIMMPortfolio portfolio;
	    
	    //final private String[]  CreditMaturityBuckets = {"1y","2y","3y","5y","10y"};
        final private String[]  IRMaturityBuckets = {"2w","1m","3m","6m","1y","2y","3y","5y","10y","15y","20y","30y"};

	    private HashMap<String/*RiskClass*/,List<HashMap<String/*curveIndexName*/,
	                HashMap<String/*maturityBucket*/,RandomVariableInterface>>>> deltaSensitivities = new HashMap<String,List<HashMap<String,HashMap<String,RandomVariableInterface>>>>(); // currently only for InterestRate riskClass
	    private RandomVariableInterface vegaSensitivity=null;
	    
	    private HashMap<Double/*time*/,RandomVariableInterface/*survivalIndicator*/> probabilityMap = new HashMap<Double/*time*/,RandomVariableInterface/*survivalIndicator*/>();
	    
	    private PortfolioInstrument(SIMMClassifiedProduct product, SIMMPortfolio portfolio){
		   this.classifiedProduct=product;
		   this.portfolio = portfolio;
	    }
	
	    /**Calculates the sensitivity of this product if the requested sensitivity is available. 
	     * 
	     * @param productClass The SIMM product class of this product (RatesFx etc.)
	     * @param riskClass The SIMM risk class of this product (InterestRate etc.)
	     * @param maturityBucket The SIMM maturity bucket for the case of Interest or Credit risk class
	     * @param curveIndexNames The name of the relevant curves for this product (OIS, Libor6m etc.)
	     * @param bucketKey The SIMM bucket key of this product (it is the currency for InterestRate risk class)
	     * @param riskType The risk type: delta, vega or curvature
	     * @param evaluationTime The evaluation time
	     */
	    public RandomVariableInterface getSensitivity(String productClass, 
			                                      String riskClass, 
			                                      String maturityBucket, // only for IR and Credit risk class, null otherwise
			                                      String curveIndexName, // null if riskClass not IR
			                                      String bucketKey,      // currency for IR otherwise bucket nr.
			                                      String riskType, double evaluationTime) throws CalculationException, SolverException, CloneNotSupportedException{
	       		   
	       RandomVariableInterface result = null;	   
		   
	       if(!classifiedProduct.getHasOptionality() && riskType!="delta") return new RandomVariable(0.0);		   
	       
		   if(classifiedProduct.getProduct() instanceof Swaption) resetSwaptionGradient(evaluationTime);
		   
		   if(evaluationTime!=lastEvaluationTime) {
			   clearMaps();  // Clear the deltaSensitivity Map. It needs to be reset at each time step.
			   setConditionalExpectationOperator(evaluationTime, model, this);
		   }
		   		
		   if(productClass==classifiedProduct.getProductClass() && Arrays.asList(classifiedProduct.getRiskClasses()).contains(riskClass)){
		   
		   switch(riskType){
		      case("delta"): 
			      switch(riskClass){
			          case("InterestRate"):
			 
			              if(Arrays.asList(classifiedProduct.getCurveIndexNames()).contains(curveIndexName) & bucketKey==classifiedProduct.getCurrency()){
				              // There exists a sensitivity. Check if the sensitivities (on all maturityBuckets) have already been calculated for given riskClass and riskType)
			            	  
			            	  if(!deltaSensitivities.containsKey(riskClass) || !deltaSensitivities.get(riskClass).stream().filter(n-> n.containsKey(curveIndexName)).findAny().isPresent()){
					            
			            		// The sensitivities need to be calculated for the given riskClass and riskType
			            		RandomVariableInterface[] deltaSensis;          // Sensitivities dVdS on LiborPeriodDiscretization
			            		RandomVariableInterface[] maturityBucketSensis; // Sensitivities mapped on the SIMM Buckets
			            				            		
			            		if(sensitivityMode != SensitivityMode.Stochastic){
			            			
			            			maturityBucketSensis = sensitivityInterpolation.getDeltaSensitivities(riskClass, curveIndexName, evaluationTime, model);
			            			
			            		    double initialMeltingTime = sensitivityInterpolation.getInitialMeltingTime(evaluationTime);	            				            		    
			            			
			            		    //if(curveIndexName=="Libor6m") System.out.println(maturityBucketSensis[0].getAverage() + "\t" + maturityBucketSensis[1].getAverage() + "\t" + maturityBucketSensis[2].getAverage() + "\t" + maturityBucketSensis[3].getAverage() + "\t" + maturityBucketSensis[4].getAverage() + "\t" + maturityBucketSensis[5].getAverage() + "\t" + maturityBucketSensis[6].getAverage() + "\t" + maturityBucketSensis[7].getAverage() + "\t" + maturityBucketSensis[8].getAverage());
			            		    double survivalProbability = getSurvivalProbability(evaluationTime-initialMeltingTime,model,getClassifiedProduct().getIsCancelable());
			            		    maturityBucketSensis = Arrays.stream(maturityBucketSensis).map(n -> n.mult(survivalProbability)).toArray(RandomVariableInterface[]::new);
			            		
			            		} else { // SensitivityMode.Stochastic
			            			deltaSensis = doCalculateDeltaSensitivitiesIR(curveIndexName, this, evaluationTime, model);
					                maturityBucketSensis = getSensitivitiesOnBuckets(deltaSensis,"InterestRate",null); // currently only for riskClass IR
			            		}
			            		
					            // Create a new element of the curveIndex List for given risk class		         
					            HashMap<String,HashMap<String,RandomVariableInterface>> curveIndexNameSensiMap = new HashMap<String,HashMap<String,RandomVariableInterface>>();
					            HashMap<String,RandomVariableInterface> bucketSensitivities = new HashMap<String,RandomVariableInterface>();
					           
					            for(int i=0;i<IRMaturityBuckets.length;i++) bucketSensitivities.put(IRMaturityBuckets[i], maturityBucketSensis[i]);
					            curveIndexNameSensiMap.put(curveIndexName,bucketSensitivities);
					            
					            // Check if list already exist
					            if(deltaSensitivities.containsKey(riskClass)) deltaSensitivities.get(riskClass).add(curveIndexNameSensiMap);
					            else {
					            	List<HashMap<String/*curveIndexName*/,HashMap<String/*maturityBucket*/,RandomVariableInterface>>> list = new ArrayList<HashMap<String/*curveIndexName*/,HashMap<String/*maturityBucket*/,RandomVariableInterface>>>();
					            	list.add(curveIndexNameSensiMap);
					            	deltaSensitivities.put(riskClass, list);
					            }					    					        					          
				             }
				             result = deltaSensitivities.get(riskClass).stream().filter(n -> n.containsKey(curveIndexName)).findFirst().get().get(curveIndexName).get(maturityBucket);			                  
			              } else result = new RandomVariable(0.0); // There exists no delta Sensi for risk Class InterestRate
			           break;
		               case("CreditQ"):
		               case("CreditNonQ"):
		               case("FX"):
		               case("Commodity"):
		               case("Equity"): result = null;
			       } break;
		      
		      case("vega"): 
			  case("curvature"):
			       switch(riskClass){
			           case("InterestRate"): //if(vegaSensitivity!=null) vegaSensitivity = getVegaSensitivityIR(curveIndexNames, product, evaluationTime, model);
			           case("CreditQ"):
			           case("CreditNonQ"):
			           case("FX"):
			           case("Commodity"):
			           case("Equity"): result=null;
			       }
		    }
		 }
		   
		this.lastEvaluationTime = evaluationTime;
	    return result;
	  } // end getSensitivity()
	
	  private void setGradient(LIBORModelMonteCarloSimulationInterface model, double time) throws CalculationException{
		  RandomVariableDifferentiableInterface productValue = (RandomVariableDifferentiableInterface) getProduct().getValue(time, model);
		  Map<Long, RandomVariableInterface> gradientOfProduct = productValue.getGradient();
		  this.gradientOfProduct = gradientOfProduct;
	  }
	  
	  public double getSurvivalProbability(double evaluationTime, LIBORModelMonteCarloSimulationInterface model, boolean isCancelable) throws CalculationException{
		    if(evaluationTime==0 || !isCancelable) return 1.0;
		    if(!probabilityMap.containsKey(new Double(evaluationTime))){
		       setConditionalExpectationOperator(evaluationTime, model, this);
			   RandomVariableInterface value = getProduct().getValue(evaluationTime, model);
			   RandomVariableInterface previousLifeIndicator = probabilityMap.containsKey(new Double(lastEvaluationTime)) ? probabilityMap.get(new Double(lastEvaluationTime)) : new RandomVariable(1.0);
			   RandomVariableInterface valueMeasurable = value.getConditionalExpectation(conditionalExpectationOperator);
			   valueMeasurable = valueMeasurable.barrier(previousLifeIndicator.mult(-1.0), valueMeasurable.mult(previousLifeIndicator), valueMeasurable);
			   RandomVariableInterface currentLifeIndicator = valueMeasurable.barrier(valueMeasurable.mult(-1.0), new RandomVariable(0.0), new RandomVariable(1.0));
		       probabilityMap.put(new Double(evaluationTime), currentLifeIndicator);
		    }
		    RandomVariableInterface currentLifeIndicator = probabilityMap.get(new Double(evaluationTime));
		    this.lastEvaluationTime = evaluationTime;
			return currentLifeIndicator.getAverage();
		}
	  
	  public void clearMaps(){
		   if(this.deltaSensitivities!=null) this.deltaSensitivities.clear();
		   this.vegaSensitivity = null;
	  }
	  
	  public void clearGradient(){
		  this.gradientOfProduct=null;
	  }
	    
	  private void resetSwaptionGradient(double evaluationTime) throws CalculationException{
		  double exerciseDate = ((Swaption)classifiedProduct.getProduct()).getExerciseDate();
          if(((Swaption) classifiedProduct.getProduct()).getDeliveryType()=="Physical" && evaluationTime>=exerciseDate && lastEvaluationTime < exerciseDate){
				      this.gradientOfProduct = ((Swaption)classifiedProduct.getProduct()).getSwapGradient(model);
          }
          
	  }
	   
	  public SIMMClassifiedProduct getClassifiedProduct(){
		  return this.classifiedProduct;
	    }
	  
	  public AbstractLIBORMonteCarloProduct getProduct(){
	    	return getClassifiedProduct().getProduct();
	    }

	  public Map<Long, RandomVariableInterface> getGradient(LIBORModelMonteCarloSimulationInterface model) throws CalculationException {
		  if(gradientOfProduct==null) setGradient(model,0.0);
		  return this.gradientOfProduct;
	    }
	  
	  public void setSensitivityInterpolation() throws CalculationException{
		  this.sensitivityInterpolation = new SensitivityInterpolation(this);
	  }

  }// end class PortfolioInstrument
	
	
	
	/**Construct a <code> SIMMPortfolio </code> 
	 * 
	 * @param classifiedProducts The portfolio products 
	 * @param calculationCurrency The calculation currency
	 * @param method The libor weight adjustment method
	 */
	public SIMMPortfolio(SIMMClassifiedProduct[] classifiedProducts,
			             String calculationCurrency,
			             SensitivityMode sensiMode,
			             WeightToLiborAdjustmentMethod method,
			             double sensiResetStep){

		  this.portfolioProducts = createPortfolioInstruments(classifiedProducts);
		  this.calculationCCY = calculationCurrency;
		  this.sensitivityMode = sensiMode;
		  this.liborWeightMethod = method;
		  this.sensiResetStep = sensiResetStep;
	}
	
	
	@Override
	public RandomVariableInterface getValue(double evaluationTime, LIBORModelMonteCarloSimulationInterface model) throws CalculationException{
		if(this.model==null || !model.equals(this.model)) { // At inception (t=0) or if the model is reset
			this.model = model;      //...the (new) model must be set
			clearPortfolio();        //...the maps of classifiedSIMMProducts are reset to null (they have to be recalculated under the new model)
	        setPortfolioGradients(); // Set the gradient of each PortfolioProduct
	        setPortfolioInterpolationSchemes();
			if(liborWeightMethod == WeightToLiborAdjustmentMethod.Constant) getConstantWeightAdjustment(model);
			this.SIMMScheme= new SIMMSchemeMain(this,this.calculationCCY);
		}
		return SIMMScheme.getValue(evaluationTime);
	}
	

	/**Calculate the sensitivities dV/dS with respect to all swap rates for given product and curve. This applies to the risk class Interest Rates only.
	 * 
	 * @param curveIndexName The name of the curve to be considered (OIS, LiborXm)
	 * @param product The product whose sensitivity is to be considered
	 * @param evaluationTime The time at which the initial margin is calculated
	 * @param model The Libor market model
	 * @return The sensitivities dV/dS i.e. with respect to swap rates.
	 * @throws SolverException
	 * @throws CloneNotSupportedException
	 * @throws CalculationException
	 * 
	 */
	public RandomVariableInterface[] doCalculateDeltaSensitivitiesIR(String curveIndexName, // include inflation risk, ccybasis and other tenors 
                                                                     PortfolioInstrument product, 
                                                                     double evaluationTime,
                                                                     LIBORModelMonteCarloSimulationInterface model) throws SolverException, CloneNotSupportedException, CalculationException{
		RandomVariableInterface[] delta;
		
		if(curveIndexName!="OIS"){ 
		  RandomVariableInterface[] dVdL = getValueLiborSensitivities(product, evaluationTime, model);
		  // Calculate dV/dS = dV/dL * dL/dS
		  delta = getValueSwapSensitivities(evaluationTime, dVdL, model);
		} else { // CurveIndexName == OIS
		  delta = getDiscountCurveSensitivities(product,evaluationTime,model);
		}
		return delta;
	}
	
	
	/**Calculate the sensitivities of the value of a product w.r.t. swap rates given the Libor sensitivities dV/dL
	 * 
	 * @param evaluationTime The time of evaluation
	 * @param dVdL The vector of derivatives dV/dL = dV/dL_0,...,dV/dL_n
	 * @param model The Libor Market Model
	 * @return The derivatives dV/dS 
	 * @throws CalculationException
	 */
	private RandomVariableInterface[] getValueSwapSensitivities(double evaluationTime, 
																RandomVariableInterface[] dVdL,
																LIBORModelMonteCarloSimulationInterface model) throws CalculationException{
		// the following line will be removed later. Just checking how timeGridAdjustment affects the result
		int timeGridIndicator = 0; if(!isUseTimeGridAdjustment && !onLiborPeriodDiscretization(evaluationTime,model)) timeGridIndicator = 1;
		
		RandomVariableInterface[] delta = new RandomVariableInterface[dVdL.length-timeGridIndicator];
		RandomVariableInterface[][] dLdS;
		if(this.liborWeightMethod == WeightToLiborAdjustmentMethod.Stochastic){
			   dLdS = getLiborSwapSensitivities(evaluationTime, model);
		} else dLdS = getConstantWeightAdjustment(model);
		// Calculate Sensitivities wrt Swaps
		// return multiply(dVdL,dLdS);
		for(int swapIndex = 0; swapIndex<dVdL.length-timeGridIndicator; swapIndex++){
			RandomVariableInterface dVdS  =new RandomVariable(0.0);
			RandomVariableInterface factor;
			for(int liborIndex=0;liborIndex<dVdL.length-timeGridIndicator;liborIndex++){
			    factor = dLdS[liborIndex][swapIndex]==null ?  new RandomVariable(0.0) : dLdS[liborIndex][swapIndex];
			    dVdS = dVdS.addProduct(dVdL[liborIndex+timeGridIndicator], factor);
		    }
			delta[swapIndex]=dVdS;
		}
		return delta;
	 }
	
	/**Performs rebucketing of sensitivities to the SIMM buckets by linear interpolation (Source: Master Thesis of Jamal Issa, modified).
	 * 
	 * @param sensitivities The sensitivities wrt swap rates dV/dS
	 * @param riskClass The risk class
	 * @param riskFactorDays The number of days corresponding to the sensitivities
	 * @return The sensitivities on the SIMM maturity buckets
	 */
	public RandomVariableInterface[] getSensitivitiesOnBuckets(RandomVariableInterface[] sensitivities, String riskClass, int[] riskFactorDays){
		//rebucketing to SIMM structure(buckets: 2w, 1m, 3m, 6m, 1y, 2y, 3y, 5y, 10y, 15y, 20y, 30y)	
		int[] riskFactorsSIMM = riskClass=="InterestRate" ? new int[] {14, 30, 90, 180, 365, 730, 1095, 1825, 3650, 5475, 7300, 10950} : /*Credit*/ new int[] {365, 730, 1095, 1825, 3650};	
		RandomVariableInterface[] deltaSIMM = new RandomVariableInterface[riskFactorsSIMM.length];
		for(int i = 0;i<deltaSIMM.length;i++) deltaSIMM[i] = new RandomVariable(0.0);
		if(riskFactorDays==null){// in case of sensitivities dV/dS at each time of the LiborPeriodDiscretization
		   riskFactorDays = new int[sensitivities.length];
		   // act/365 as default daycount convention
		   for(int i=0;i<sensitivities.length;i++) riskFactorDays[i] = (int)Math.round(365 * model.getLiborPeriodDiscretization().getTime(i+1));	
		}
		int counter = 0;
		for(int simmFactor =0; simmFactor<riskFactorsSIMM.length;simmFactor++){
			for(int i = counter; i<sensitivities.length; i++){
				
							    
					if(riskFactorDays[i] < riskFactorsSIMM[0]){
						deltaSIMM[0] = deltaSIMM[0].add(sensitivities[i]);
						counter++;
					}
					else{
						if(riskFactorDays[i] >= riskFactorsSIMM[riskFactorsSIMM.length-1]){
							deltaSIMM[deltaSIMM.length-1] = deltaSIMM[deltaSIMM.length-1].add(sensitivities[i]);
						}
					
						else{
							if(riskFactorDays[i] >= riskFactorsSIMM[simmFactor] && riskFactorDays[i] < riskFactorsSIMM[simmFactor+1]){
					
							deltaSIMM[simmFactor] = deltaSIMM[simmFactor].addProduct(sensitivities[i],((double)(riskFactorsSIMM[simmFactor+1] - riskFactorDays[i]) / (riskFactorsSIMM[simmFactor+1]-riskFactorsSIMM[simmFactor])));
							deltaSIMM[simmFactor+1] = deltaSIMM[simmFactor+1].addProduct(sensitivities[i],((double)(riskFactorDays[i]-riskFactorsSIMM[simmFactor]) / (riskFactorsSIMM[simmFactor+1]-riskFactorsSIMM[simmFactor])));
							counter++;
							}							
							else{
							break;
							}
						}
					
					}
			}
			
		}
		
	return deltaSIMM;		
			
	}
	
	
	/**Calculates dL/dS 
	 * 
	 * @param evaluationTime The time at which the sensitivity is calculated
	 * @param model The Libor market model
	 * @return The matrix dL/dS 
	 * @throws CalculationException
	 */
	private RandomVariableInterface[][] getLiborSwapSensitivities(double evaluationTime, LIBORModelMonteCarloSimulationInterface model) throws CalculationException{
		setConditionalExpectationOperator(evaluationTime, model, null);
		RandomVariableInterface[][] dLdS=null;
		double liborPeriodLength = model.getLiborPeriodDiscretization().getTimeStep(0);
		
	    // Get index of first Libor starting >= evaluationTime
		int nextLiborIndex = model.getLiborPeriodDiscretization().getTimeIndexNearestGreaterOrEqual(evaluationTime);
		int numberOfRemainingLibors = model.getNumberOfLibors()-nextLiborIndex;
		dLdS = new RandomVariableInterface [numberOfRemainingLibors][numberOfRemainingLibors];
					
		// Calculate dLdS directly  
		dLdS[0][0]=new RandomVariable(1.0);
		double discountTime = evaluationTime+liborPeriodLength;
		RandomVariableInterface sumDf = model.getNumeraire(discountTime).invert();
		for(int liborIndex = 1; liborIndex<dLdS.length;liborIndex++){
		    discountTime +=model.getLiborPeriodDiscretization().getTimeStep(0);
		    RandomVariableInterface df = model.getNumeraire(discountTime).invert();
		    RandomVariableInterface denominator = df.getConditionalExpectation(conditionalExpectationOperator);
		    dLdS[liborIndex][liborIndex-1]=sumDf.getConditionalExpectation(conditionalExpectationOperator).div(denominator).mult(-1.0);//dLdS[liborIndex][liborIndex-1]=-sumDf.getConditionalExpectation(conditionalExpectationOperator).getAverage()/denominator;
		    sumDf = sumDf.add(df);
		    dLdS[liborIndex][liborIndex] = sumDf.getConditionalExpectation(conditionalExpectationOperator).div(denominator);
		}
		
		return dLdS;
	}
	
	
	
	/**Since dV/dL is wrt the incorrect Libor times this function provides a matrix dL/dL to be multiplied with dV/dL in order to 
	 * have the correct libor times starting at evaluationTime. 
	 * @param evaluationTime The time at which the adjustment should be calculated.
	 * @param model The Libor market model
	 * @return Pseudo Inverse of derivative band matrix; Identity matrix in case of evaluationTime on LiborPeriodDiscretization; 
	 * @throws CalculationException
	 */
	private RandomVariableInterface[][] getLiborTimeGridAdjustment(double evaluationTime, LIBORModelMonteCarloSimulationInterface model) throws CalculationException{
		int numberOfRemainingLibors = getNumberOfRemainingLibors(evaluationTime,model);
		
		// If evaluationTime lies on Libor Time Grid - return identity matrix
		if (onLiborPeriodDiscretization(evaluationTime,model)) {
			RandomVariableInterface[][] dLdL = new RandomVariableInterface[numberOfRemainingLibors][numberOfRemainingLibors];
			for(int i=0;i<dLdL.length;i++) dLdL[i][i]=new RandomVariable(1.0);
		    return dLdL;
		}
		
		// Calculate dLdL. It is a (n-1)x n Matrix!
		RandomVariableInterface[][] dLdL = new RandomVariableInterface[numberOfRemainingLibors][numberOfRemainingLibors+1];
		double swapTenorLength = model.getLiborPeriodDiscretization().getTimeStep(0); // Model must have same tenor as swap!
		double timeOfFirstLiborPriorToEval = getPreviousLiborTime(evaluationTime,model);
		int timeIndexAtEvaluationTime = model.getTimeDiscretization().getTimeIndexNearestGreaterOrEqual(evaluationTime);
		int timeIndexAtFirstLiborPriorToEval = model.getTimeDiscretization().getTimeIndexNearestGreaterOrEqual(timeOfFirstLiborPriorToEval);
		
		for(int liborIndex = 0; liborIndex <numberOfRemainingLibors; liborIndex++){
			double liborTime = evaluationTime+liborIndex*swapTenorLength; // t+j*\Delta T
		    int    previousLiborIndex = model.getLiborPeriodDiscretization().getTimeIndexNearestLessOrEqual(liborTime);
		    double previousLiborTime = model.getLiborPeriodDiscretization().getTime(previousLiborIndex);
		    double firstNextLiborTime = model.getLiborPeriodDiscretization().getTime(previousLiborIndex+1);
		    double secondNextLiborTime = model.getLiborPeriodDiscretization().getTime(previousLiborIndex+2);
		    double factor1 = (secondNextLiborTime-(liborTime+swapTenorLength))/(secondNextLiborTime-firstNextLiborTime);
		    double factor2 = (liborTime-previousLiborTime)/(firstNextLiborTime-previousLiborTime);
		    int    timeIndex = liborIndex==0 ? timeIndexAtFirstLiborPriorToEval : timeIndexAtEvaluationTime;
		    // Get Libors
		    RandomVariableInterface previousLibor = model.getLIBOR(timeIndex, previousLiborIndex);     
		    RandomVariableInterface nextLibor     = model.getLIBOR(timeIndex, previousLiborIndex + 1); 
		    RandomVariableInterface logInterpol = nextLibor.mult(secondNextLiborTime-firstNextLiborTime).add(1.0).log().mult(-factor1);
		                            logInterpol = logInterpol.add(previousLibor.mult(firstNextLiborTime-previousLiborTime).add(1.0).log().mult(-factor2)).exp();
		    // Set derivatives
		    dLdL[liborIndex][liborIndex]   = nextLibor.mult(secondNextLiborTime-firstNextLiborTime).add(1.0).mult(logInterpol).mult(1-factor2);// dLdL_i-1
		    dLdL[liborIndex][liborIndex+1] = previousLibor.mult(firstNextLiborTime-previousLiborTime).add(1.0).mult(logInterpol).mult(1-factor1);
		}
		
		// dLdL is (n-1) x n matrix. Get PseudoInverse for all paths and then put it back together as RV
		return getPseudoInverse(dLdL);
	}
	
	
	/**Calculates the row vector dV/dL
	 * 
	 * @param product The <\code> PortfolioProduct <\code> whose sensitivities should be calculated
	 * @param evaluationTime The time at which the forward sensistivity dVdL is calculated
	 * @param model The Libor market model
	 * @return The forward sensisivity dVdL (as a row vector)
	 * @throws CalculationException
	 */
	private RandomVariableInterface[] getValueLiborSensitivities(PortfolioInstrument product, 
			                                                    double evaluationTime,
			                                                    LIBORModelMonteCarloSimulationInterface model) throws CalculationException{
		
		// For swaps we calculate it analytically
		if(product.getProduct() instanceof SimpleSwap) {
			double[] fixingDates = ((SimpleSwap)product.getProduct()).getFixingDates();
			double   notional = ((SimpleSwap)product.getProduct()).getNotional();
			RandomVariableInterface[] swapSensis = getAnalyticSwapSensitivities(evaluationTime, fixingDates ,model, "Libor");
			swapSensis = Arrays.stream(swapSensis).map(n->n.mult(notional)).toArray(RandomVariableInterface[]::new);
			RandomVariableInterface[][] dLdL = getLiborTimeGridAdjustment(evaluationTime, model);
			return multiply(swapSensis,dLdL);
		}
		
		setConditionalExpectationOperator(evaluationTime, model,product);
		RandomVariableDifferentiableInterface numeraire = (RandomVariableDifferentiableInterface) model.getNumeraire(evaluationTime);
		
		// Calculate forward sensitivities
		int numberOfRemainingLibors = getNumberOfRemainingLibors(evaluationTime,model);
		int numberOfSensis = evaluationTime == getNextLiborTime(evaluationTime,model) ? numberOfRemainingLibors : numberOfRemainingLibors+1;
		RandomVariableInterface[] valueLiborSensitivities = new RandomVariableInterface[numberOfSensis];
		int timeIndexAtEval = model.getTimeDiscretization().getTimeIndexNearestLessOrEqual(evaluationTime);
		
		// Set all entries of dVdL
		// Set dVdL for last libor which is already fixed (if applicable)
		int timeGridIndicator = 0;
		int lastLiborIndex = model.getLiborPeriodDiscretization().getTimeIndexNearestLessOrEqual(evaluationTime);
		
		if(numberOfSensis!=numberOfRemainingLibors){
			timeGridIndicator = 1;
			double lastLiborTime = model.getLiborPeriodDiscretization().getTime(lastLiborIndex);
			RandomVariableInterface lastLibor = model.getLIBOR(model.getTimeDiscretization().getTimeIndex(lastLiborTime), lastLiborIndex);
			RandomVariableInterface dVdL = getProductValueDerivative(product,lastLibor,model);
			valueLiborSensitivities[0] = dVdL.mult(numeraire);
		}
		
		for(int liborIndex=lastLiborIndex+timeGridIndicator;liborIndex<model.getNumberOfLibors(); liborIndex++){
			RandomVariableInterface liborAtTimeIndex = model.getLIBOR(timeIndexAtEval, liborIndex);
		    RandomVariableInterface dVdL = getProductValueDerivative(product,liborAtTimeIndex,model);
//		    RandomVariableInterface barrier = new RandomVariable(1.0);
//		    if(product.getProduct() instanceof Swaption && evaluationTime >= ((Swaption)product.getProduct()).getExerciseDate())  barrier = new RandomVariable(1.0).barrier(dVdL.abs().mult(-1.0), new RandomVariable(0.0), new RandomVariable(1.0));
		    valueLiborSensitivities[liborIndex-lastLiborIndex] = dVdL.mult(numeraire).getConditionalExpectation(conditionalExpectationOperator);//.mult(barrier);
		}
		
		if(isUseTimeGridAdjustment){
		// Up to now dVdL is wrt the Libors on the LiborPeriodDiscretization. Adjust it such that we have dVdL wrt Libors starting at evaluationTime 
		RandomVariableInterface[][] dLdL = getLiborTimeGridAdjustment(evaluationTime, model);
		RandomVariableInterface[] dVdLAdjusted = multiply(valueLiborSensitivities,dLdL);
		
		return dVdLAdjusted; 
		} else return valueLiborSensitivities;
	}
	
	/**Calculates dV/dS where S are swap rates of the discount curve.
	 * 
	 * @param product The <\code> PortfolioProduct <\code> whose sensitivities should be calculated
	 * @param evaluationTime The time at which dVdS is calculated
	 * @param model The Libor market model
	 * @return The row vector of sensitivities wrt swap rates from discount curve.
	 * @throws CalculationException 
	 */
	private RandomVariableInterface[] getDiscountCurveSensitivities(PortfolioInstrument product, 
			                                                        double evaluationTime,
			                                                        LIBORModelMonteCarloSimulationInterface model) throws CalculationException{
//		// We calculate dV/dP * dP/dS
//		setConditionalExpectationOperator(evaluationTime, model, product);
//		DiscountCurve discountCurve = (DiscountCurve) model.getModel().getDiscountCurve();
//
//		double[] discountCurvePillars = discountCurve.getPillarTimes();
//		
//		// Remove first entry from pillars if it is at time 0.
//		int index = discountCurvePillars[0]==0 ? 1 : 0;
//		double[] pillars = new double[discountCurvePillars.length-index];
//		for(int i=0;i<pillars.length;i++) pillars[i]=discountCurvePillars[i+index];
//		
//		// Get gradient for value of product evaluated at t. This is necessary to only account for sensis of cash flows w.r.t P(T,0) after t.
//		RandomVariableInterface  value = product.getProduct().getValue(evaluationTime, model); 
//		Map<Long, RandomVariableInterface> gradientOfProduct = ((RandomVariableDifferentiableInterface) value).getGradient();
		int numberOfP = getNumberOfRemainingLibors(evaluationTime,model);
		
//		// lastPillarIndex = the index of the relevant pillar on the left side 
//		int lastPillarIndex = evaluationTime>pillars[0] ? new TimeDiscretization(pillars).getTimeIndexNearestLessOrEqual(evaluationTime) : 0;
//		
//        double liborPeriodLength = model.getLiborPeriodDiscretization().getTimeStep(0);
//        
//        // dVdP: The derivatives w.r.t the deterministic OIS curve i.e. dV(t)/dP(T,0)
//		RandomVariableInterface[] dVdP = new RandomVariableInterface[pillars.length-lastPillarIndex];
//     
//        TimeDiscretization curveTimes = new TimeDiscretization(pillars);
//		RandomVariableInterface[] discountFactors = new RandomVariableInterface[pillars.length];
//		
//		// get discount factors
//		for(int i=0;i<pillars.length;i++) discountFactors[i]=discountCurve.getDiscountFactor(pillars[i]);
//		
//		// dV(t)/dP(T_i;0)
//		for(int i=lastPillarIndex;i<pillars.length;i++){
//			dVdP[i-lastPillarIndex] = getDerivative(gradientOfProduct, discountFactors[i]).getConditionalExpectation(conditionalExpectationOperator);
//		}
//		
//		// Get dP(T_i;0)/dP(t+i\delta T;0): Linear interpolation on log value per time
//		RandomVariableInterface[][] dPdP = new RandomVariableInterface[numberOfP][pillars.length-lastPillarIndex];
//		
//		for(int i=0;i<dPdP.length;i++){
//			double discountTime = evaluationTime + (i+1) * liborPeriodLength;
//			if(discountTime < pillars[0]) {
//				double term = Math.pow(discountTime/pillars[0],2.0);
//				dPdP[i][0]=discountFactors[0].invert().mult(term).mult(discountFactors[0].log().mult(term).exp());
//				continue;
//			}
//			
//			// Get upper and lower index
//			int lowerIndex = curveTimes.getTimeIndexNearestLessOrEqual(discountTime); // as 0 is included in time discretization but not in pillars
//			lowerIndex = lowerIndex < 0 ? 0 : lowerIndex;
//			int upperIndex = lowerIndex+1;
//			
//			// Actual interpolation
//			double delta = (discountTime-pillars[lowerIndex])/curveTimes.getTimeStep(lowerIndex);
//			RandomVariableInterface summand1 = discountFactors[lowerIndex].log().mult((1-delta)/pillars[lowerIndex]);
//			RandomVariableInterface summand2 = discountFactors[upperIndex].log().mult(delta/pillars[upperIndex]);
//			RandomVariableInterface factor   = summand1.add(summand2).mult(discountTime).exp();
//			//Math.exp(((1-delta)/pillars[lowerIndex]*Math.log(discountFactors[lowerIndex])+delta/pillars[upperIndex]*Math.log(discountFactors[upperIndex]))*discountTime);
//			dPdP[i][lowerIndex-lastPillarIndex]=factor.div(discountFactors[lowerIndex]).mult((1-delta)/pillars[lowerIndex]*discountTime);
//			dPdP[i][upperIndex-lastPillarIndex]=factor.div(discountFactors[upperIndex]).mult(delta/pillars[upperIndex]*discountTime);
//		}
//		
//		// dVdP: dV(t)/dP(t+i\delta T;0)
//        dVdP = multiply(dVdP,getPseudoInverse(dPdP));
//        
//        // Get dV(t)/dP(t+i\delta T;t)
//        for(int i=0; i < dVdP.length; i++){
//        	double discountTime = evaluationTime + (i+1) * liborPeriodLength;
//        	RandomVariableInterface df = discountCurve.getDiscountFactor(discountTime);
//        	RandomVariableInterface bond = model.getNumeraire(evaluationTime).div(model.getNumeraire(discountTime)).getConditionalExpectation(conditionalExpectationOperator);
//        	dVdP[i] = dVdP[i].mult(df).div(bond);
//        }
        RandomVariableInterface[] dVdP = new RandomVariable[numberOfP]; Arrays.fill(dVdP, new RandomVariable(0.0));
        // Get dV(t)/dS(t)
		RandomVariableInterface[][] dPdS = getBondSwapSensitivity(evaluationTime, model);
		//for(int i=0;i<dPdS.length;i++) Arrays.fill(dPdS[i], new RandomVariable(0.0)); // remove later
		return multiply(dVdP,dPdS);
	}
	
	
	/**Calculates dPdS in a single curve context. Used for calculating sensis with respect to discount curve.
	 * 
	 * @param evaluationTime The time at which the initial margin is calculated
	 * @param model The Libor market model
	 * @return The sensitivity of the discount curve (bonds) wrt to swap rates of the same curve.
	 * @throws CalculationException 
	 */
	private RandomVariableInterface[][] getBondSwapSensitivity(double evaluationTime, LIBORModelMonteCarloSimulationInterface model) throws CalculationException{
		int numberOfBonds = getNumberOfRemainingLibors(evaluationTime,model);
		RandomVariableInterface sum= new RandomVariable(0.0);
		RandomVariableInterface[][] dSdP = new RandomVariableInterface[numberOfBonds][numberOfBonds];
		for(int bondIndex=0;bondIndex<dSdP[0].length;bondIndex++){
			RandomVariableInterface bond = model.getNumeraire(evaluationTime+(bondIndex+1)*0.5).invert().mult(model.getNumeraire(evaluationTime)).getConditionalExpectation(conditionalExpectationOperator);
		    sum = sum.add(bond);
		    for(int swapIndex=0;swapIndex<dSdP.length;swapIndex++){
		    	if(swapIndex<bondIndex) dSdP[swapIndex][bondIndex] = new RandomVariable(0.0);
		    	else if(swapIndex==bondIndex) dSdP[swapIndex][bondIndex] = sum.add(1.0).sub(bond).mult(-1.0).div(sum.squared());
		    	else dSdP[swapIndex][bondIndex] = bond.sub(1.0).div(sum.squared());    	
		    }
		} 
		return getPseudoInverse(dSdP); // PseudoInverse == Inverse for n x n matrix.
	}
	
	/** Calculate Swap Sensitivities dV/dL analytically
	 * 
	 * @param evaluationTime The time of evaluation
	 * @param fixingDates The fixing times of the swap floating leg
	 * @param model The LIBOR model used for simulation of the Libors
	 * @param withRespectTo "Libor" or "OIS"
	 * @return
	 * @throws CalculationException
	 */
	public  RandomVariableInterface[] getAnalyticSwapSensitivities(double evaluationTime, 
            													   double[] fixingDates,
            													   LIBORModelMonteCarloSimulationInterface model,
            													   String withRespectTo) throws CalculationException{

			setConditionalExpectationOperator(evaluationTime,model,null /*Swap: No special treatment*/);
			
			//  periodIndex: Index of the swap period at evaluationTime
			int periodIndex = new TimeDiscretization(fixingDates).getTimeIndexNearestLessOrEqual(evaluationTime); 
			periodIndex = periodIndex < 0 ? 0 : periodIndex;
			
			//  firstLiborIndex: Index of the Libor on the first period of the swap
			int currentLiborIndex = model.getLiborPeriodDiscretization().getTimeIndexNearestLessOrEqual(evaluationTime);
			int firstLiborIndex   = fixingDates[0] > evaluationTime ? model.getLiborPeriodDiscretization().getTimeIndexNearestLessOrEqual(fixingDates[0]):currentLiborIndex;
			
			int numberOfRemainingLibors = getNumberOfRemainingLibors(evaluationTime,model);
			int numberOfSensis = evaluationTime == getNextLiborTime(evaluationTime,model) ? numberOfRemainingLibors : numberOfRemainingLibors+1;
			RandomVariableInterface[] sensis = new RandomVariableInterface[numberOfSensis]; 
			Arrays.fill(sensis, new RandomVariable(0.0));

			RandomVariableInterface numeraireAtEval = model.getNumeraire(evaluationTime);
			double periodLength = model.getLiborPeriodDiscretization().getTimeStep(0);

			switch(withRespectTo){
			
			   case("Libor"):
				   
			       // Actual Sensitivity Calculation: dV/dL = P(T,t)*periodLength
			       for(int liborIndex=currentLiborIndex;liborIndex<numberOfSensis+currentLiborIndex;liborIndex++){
				       int i = liborIndex < firstLiborIndex ? 0 : liborIndex-firstLiborIndex+1;
				       if(!(i>fixingDates.length-periodIndex || i==0) ){ 		
					       RandomVariableInterface numeraireAtPayment = model.getNumeraire(fixingDates[periodIndex+i-1]+periodLength);
					       sensis[liborIndex-currentLiborIndex]=numeraireAtEval.div(numeraireAtPayment).mult(periodLength).getConditionalExpectation(conditionalExpectationOperator);			
				       }
			       }
			       break;
			       
			   case("OIS"):
				   
			}
			
			return sensis;
	}
	

	
	


	
	//----------------------------------------------------------------------------------------------------------------------------------
	// Some auxiliary functions
	//----------------------------------------------------------------------------------------------------------------------------------

	/**Calculate Pseudo Inverse of matrix of type RandomVariableInterface[][]
	 * 
	 * @param matrix The matrix for which the pseudo inverse is calculated
	 * @return The pseudo inverse of the matrix
	 */
    private RandomVariableInterface[][] getPseudoInverse(RandomVariableInterface[][] matrix){
    	double[][][] inv = new double[matrix[0].length][matrix.length][model.getNumberOfPaths()];
		double[][] matrixOnPath = new double[matrix.length][matrix[0].length];
		for(int pathIndex=0; pathIndex<model.getNumberOfPaths(); pathIndex++){
			// Get double[][] matrix on path
			for(int i=0;i<matrixOnPath.length;i++){
				for(int j=0;j<matrixOnPath[0].length;j++){
					matrixOnPath[i][j]=matrix[i][j]==null ? 0 : matrix[i][j].get(pathIndex);
				}
			}
		    // Get Pseudo Inverse 
		    RealMatrix pseudoInverse = new SingularValueDecomposition(MatrixUtils.createRealMatrix(matrixOnPath)).getSolver().getInverse();
		    for(int j=0;j<pseudoInverse.getColumnDimension();j++){
			    double[] columnValues = pseudoInverse.getColumn(j);
			    for(int i=0;i<pseudoInverse.getRowDimension();i++){
				    inv[i][j][pathIndex]= columnValues[i];
			    }
		    }
		}
		// Wrap to RandomVariableInterface[][]
		RandomVariableInterface[][] pseudoInverse = new RandomVariableInterface[matrix[0].length][matrix.length];
		for(int i=0;i<pseudoInverse.length; i++){
			for(int j=0;j<pseudoInverse[0].length; j++){
				pseudoInverse[i][j] = new RandomVariable(0.0 /*should be evaluationTime*/,inv[i][j]);
			}
		}
		return pseudoInverse;
    }
   

	public static RandomVariableInterface[][] multiply(RandomVariableInterface[][] A,RandomVariableInterface[][] B){
		RandomVariableInterface[][] AB = new RandomVariableInterface[A.length][B.length];
		RandomVariableInterface ABproduct;
		for(int i=0;i<A.length;i++){
			for(int j=0; j<B.length; j++){
				AB[i][j] = new RandomVariable(0.0);
				for(int k=0;k<B.length;k++) {
					if(A[i][k]==null || B[k][j]==null) {ABproduct = new RandomVariable(0.0);}
					else {ABproduct = A[i][k].mult(B[k][j]);}
					AB[i][j]=AB[i][j].add(ABproduct);
				}
			}
		}
		return AB;
	}
	
	public static RandomVariableInterface[] multiply(RandomVariableInterface[] A,RandomVariableInterface[][] B){
		RandomVariableInterface[] AB = new RandomVariableInterface[B[0].length];
		RandomVariableInterface ABproduct;
		for(int i=0;i<B[0].length;i++){
				AB[i] = new RandomVariable(0.0);
				for(int k=0;k<A.length;k++) {
					if(A[k]==null || B[k][i]==null) {ABproduct = new RandomVariable(0.0);}
					else {ABproduct = A[k].mult(B[k][i]);}
					AB[i]=AB[i].add(ABproduct);
				}
		}
		return AB;
	}
	
	/**Calculates the derivative of the current portfolio product with respect to the specified parameter, dV/dX 
	 * 
	 * @param parameter The parameter with respect to which the derivative is calculated
	 * @param model The Libor market model
	 * @return dV/dX 
	 * @throws CalculationException
	 */
	private RandomVariableInterface getProductValueDerivative(PortfolioInstrument product, 
			                                                  RandomVariableInterface parameter,
			                                                  LIBORModelMonteCarloSimulationInterface model) throws CalculationException{

		RandomVariableInterface derivative = product.getGradient(model).get(((RandomVariableDifferentiableInterface)parameter).getID());
		return derivative==null ? new RandomVariable(0.0) : derivative;
	}
	
	
	private int getNumberOfRemainingLibors(double evaluationTime, LIBORModelMonteCarloSimulationInterface model){
		int nextLiborIndex = model.getLiborPeriodDiscretization().getTimeIndexNearestGreaterOrEqual(evaluationTime);
		return model.getNumberOfLibors()-nextLiborIndex;
	}
	
	private double getNextLiborTime(double evaluationTime, LIBORModelMonteCarloSimulationInterface model){
		int nextLiborIndex = model.getLiborPeriodDiscretization().getTimeIndexNearestGreaterOrEqual(evaluationTime);
		return model.getLiborPeriodDiscretization().getTime(nextLiborIndex);
	}
	
	private double getPreviousLiborTime(double evaluationTime, LIBORModelMonteCarloSimulationInterface model){
		if(evaluationTime==0) return 0.0;
		int nextLiborIndex = model.getLiborPeriodDiscretization().getTimeIndexNearestGreaterOrEqual(evaluationTime);
		return model.getLiborPeriodDiscretization().getTime(nextLiborIndex-1);
	}
	
		
	private static ArrayList<RandomVariableInterface> getRegressionBasisFunctions(RandomVariableInterface[] libors, int order) {
		ArrayList<RandomVariableInterface> basisFunctions = new ArrayList<RandomVariableInterface>();
		// Create basis functions - here: 1, S, S^2, S^3, S^4
		for(int liborIndex=0; liborIndex<libors.length;liborIndex++){
		  for(int powerOfRegressionMonomial=0; powerOfRegressionMonomial<=order; powerOfRegressionMonomial++) {
			  basisFunctions.add(libors[liborIndex].pow(powerOfRegressionMonomial));
		  }
		}
		return basisFunctions;
	}
	
	/*
	 *  Getters and Setters
	 */
	
	public PortfolioInstrument getPortfolioProduct(int index) {
		return portfolioProducts[index];
	}

	
	public WeightToLiborAdjustmentMethod getLiborWeightMethod(){
		return this.liborWeightMethod;
	}
	
	
	private boolean onLiborPeriodDiscretization(double evaluationTime, LIBORModelMonteCarloSimulationInterface model){
		return (evaluationTime == getNextLiborTime(evaluationTime,model));
	}
	
	
	private void setConditionalExpectationOperator(double evaluationTime, LIBORModelMonteCarloSimulationInterface model, PortfolioInstrument product) throws CalculationException{
		RandomVariableInterface exerciseIndicator = new RandomVariable(1.0);
		
		// Bermudan Swaption
		if(product!=null && product.getProduct() instanceof BermudanSwaption){
			   RandomVariableInterface exerciseTime = ((BermudanSwaption)product.getProduct()).getLastValuationExerciseTime();
			   exerciseIndicator = exerciseIndicator.barrier(new RandomVariable(exerciseTime.sub(evaluationTime).mult(-1.0)),new RandomVariable(0.0),exerciseIndicator);
		}
		
		// Swaption
		if(product!=null && product.getProduct() instanceof Swaption){
			double exerciseTime = ((Swaption)product.getProduct()).getExerciseDate();
			if(evaluationTime>=exerciseTime)  {
			   exerciseIndicator = ((Swaption)product.getProduct()).getExerciseIndicator(model); // 1 if exercised on this path		   
			}
		}
		
		
		// Create a conditional expectation estimator with some basis functions (predictor variables) for conditional expectation estimation.
        RandomVariableInterface[] regressor = new RandomVariableInterface[2];
        regressor[0]= model.getLIBOR(evaluationTime, evaluationTime,evaluationTime+model.getLiborPeriodDiscretization().getTimeStep(0)).mult(exerciseIndicator);
		regressor[1]= model.getLIBOR(evaluationTime, evaluationTime, model.getLiborPeriodDiscretization().getTime(model.getNumberOfLibors()-1)).mult(exerciseIndicator);
       	ArrayList<RandomVariableInterface> basisFunctions = getRegressionBasisFunctions(regressor, 2);
//		Alternative definition of regressors
//		RandomVariableInterface[] libors = getRemainingLibors(evaluationTime, model);
//		ArrayList<RandomVariableInterface> basisFunctions = getRegressionBasisFunctions(libors, 1 /*polyNomialOrder*/);
//		ConditionalExpectationEstimatorInterface conditionalExpectationOperator = new MonteCarloConditionalExpectationRegression(basisFunctions.toArray(new RandomVariableInterface[0]));
       	this.conditionalExpectationOperator = new MonteCarloConditionalExpectationRegression(basisFunctions.toArray(new RandomVariableInterface[0]));

	}
	
	// Delete later..
	public void setUseTimeGridAdjustment(boolean method){ 
		this.isUseTimeGridAdjustment = method;
	}
	
	
	private RandomVariableInterface[][] getPseudoInverse(double[][] matrix){
		RealMatrix pseudoInverse = new SingularValueDecomposition(MatrixUtils.createRealMatrix(matrix)).getSolver().getInverse();
		RandomVariableInterface[][] inv = new RandomVariableInterface[matrix[0].length][matrix.length];
		for(int j=0;j<pseudoInverse.getColumnDimension();j++){
		    double[] columnValues = pseudoInverse.getColumn(j);
		    for(int i=0;i<pseudoInverse.getRowDimension();i++){
			    inv[i][j]= new RandomVariable(columnValues[i]);
		    }		    
	    }
		return inv;
	}
	
	private void clearPortfolio(){
		for(int i=0;i<portfolioProducts.length;i++){
			portfolioProducts[i].clearMaps();
			portfolioProducts[i].clearGradient();
		}
		this.riskWeightToLiborAdjustments=null;
	}
	
	private void setPortfolioGradients() throws CalculationException{
		for(int i=0;i<portfolioProducts.length;i++){
			portfolioProducts[i].setGradient(model, 0.0);
		}
	}
	
	private void setPortfolioInterpolationSchemes() throws CalculationException{
		for(int i=0;i<portfolioProducts.length;i++){
			portfolioProducts[i].setSensitivityInterpolation();
		}
	}
	
	public PortfolioInstrument[] getProducts(){
		return portfolioProducts;
	}
	
	private PortfolioInstrument[] createPortfolioInstruments(SIMMClassifiedProduct[] classifiedProducts){
		PortfolioInstrument[] products = new PortfolioInstrument[classifiedProducts.length];
		for(int i=0;i<products.length;i++) products[i]= new PortfolioInstrument(classifiedProducts[i], this);
		return products;
	}
	
	private RandomVariableInterface[][] getConstantWeightAdjustment(LIBORModelMonteCarloSimulationInterface model) throws CalculationException{
		if(riskWeightToLiborAdjustments==null) {
			this.riskWeightToLiborAdjustments = getLiborSwapSensitivities(0.0 /*evaluationTime*/, model);
		}
		return riskWeightToLiborAdjustments;
	}
	
	public double getSensiResetStep(){
		return this.sensiResetStep;
	}
	
	public SensitivityMode getSensitivityMode(){
		return this.sensitivityMode;
	}
	
	public LIBORModelMonteCarloSimulationInterface getModel(){
		return this.model;
	}

	
	
//	   RandomVariableInterface[] testBuckets = new RandomVariable[IRMaturityBuckets.length];
//	   Arrays.fill(testBuckets, new RandomVariable(0.0));
//	   testBuckets[7] = new RandomVariable(-50);
//	   testBuckets[8] = new RandomVariable(200);
//	   testBuckets[6] = new RandomVariable(0);
} 