package com.ociweb.pronghorn.stage.scheduling;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ociweb.pronghorn.pipe.Pipe;
import com.ociweb.pronghorn.stage.PronghornStage;
import com.ociweb.pronghorn.util.ma.RunningStdDev;
import com.ociweb.pronghorn.util.math.PMath;
import com.ociweb.pronghorn.util.math.ScriptedSchedule;

public class ScriptedNonThreadScheduler extends StageScheduler implements Runnable {

    public static boolean debugStageOrder = false; //turn on to investigate performance issues.
	
    private static final int NS_OPERATOR_FLOOR = 10;
	private AtomicBoolean shutdownRequested;
    private long[] rates;
    private long[] lastRun;


    private long maxRate;
    public final PronghornStage[] stages;
    private static final Logger logger = LoggerFactory.getLogger(ScriptedNonThreadScheduler.class);

    private long nextRun = 0; //keeps times of the last pass so we need not check again

    private volatile Throwable firstException;//will remain null if nothing is wrong

    //Time based events will poll at least this many times over the period.
    // + ensures that the time trigger happens "near" the edge
    // + ensures that this non-thread scheduler in unit tests can capture the time delayed events.
    public static final int granularityMultiplier = 4;
    private static final long MS_TO_NS = 1_000_000;

    //when false this uses low granularity timer, this will optimize volume not latency
	public boolean lowLatencyEnforced = true;

    private int[] producersIdx;

    private Pipe[] producerInputPipes;
    private long[] producerInputPipeHeads;

    private Pipe[] inputPipes;
    private long[] inputPipeHeads;

    private AtomicInteger isRunning = new AtomicInteger(0);
    private final GraphManager graphManager;
    private String name = "";
    private PronghornStage lastRunStage = null;
  
    private static final boolean debugNonReturningStages = false;

    private ScriptedSchedule schedule = null;
    private int[] enabled; //used for turning off subGraph sequences when not in use. has jumps for jump over
    private int[] sequenceLookup; //schedule

    private boolean recordTime;    

    
    private byte[] stateArray;
    
    private void buildSchedule(int scheduleId, GraphManager graphManager, 
    		                   PronghornStage[] stages, 
    		                   boolean reverseOrder) {
    	
    	stateArray = GraphManager.stageStateArray(graphManager);    	
    	recordTime = GraphManager.isTelemetryEnabled(graphManager);
    	
    	if (null==stages) {
    		schedule = new ScriptedSchedule(0, new int[0], 0);
    		//skipScript = new int[0];
    		return;
    	}
    	
        // Pre-allocate rates based on number of stages.
    	final int defaultValue = 2_000_000;
        long rates[] = new long[stages.length];

        int k = stages.length;
        while (--k>=0) {
        	
        	//set thread name
        	if (scheduleId>=0) {
        		GraphManager.recordThreadGroup(stages[k], scheduleId, graphManager);
        	}
        	
        	// Determine rates for each stage.
			long scheduleRate = Long.valueOf(String.valueOf(GraphManager.getNota(graphManager, stages[k], GraphManager.SCHEDULE_RATE, defaultValue)));
            rates[k] = scheduleRate;
        }

        // Build the script.
        schedule = PMath.buildScriptedSchedule(rates, reverseOrder);

        boolean newBlock = true;
        int scriptLength = schedule.script.length;
        
        sequenceLookup = new int[scriptLength];
        enabled = new int[scriptLength];//zeros are not used
                
        int lastBlockIdx = -1;
        int blockRun = 0;
        int blockId = 0;
        for(int i = 0; i<scriptLength; i++) {
        	
        	int value = schedule.script[i];
     
        	if (newBlock) {
        		//record this position as a new block
        		sequenceLookup[blockId] = i; //allows lookup to the right index for enable/disable call and is disbled
        		lastBlockIdx = i;
        		blockRun = 0;
        		     		
        		//TODO: need all the pipes associated with this sequence
        		//TODO: need all the pipes going into this sequence;
        		
        		
        		
        		
        		blockId++;
        	}
        	blockRun++;
        	
        	if (-1==value) { //end of run
        	    //write the skip size but negative to enable all blocks for now.
        		enabled[lastBlockIdx] = (0-blockRun);
        		
        		newBlock = true;
        	} else {
        		newBlock = false;
        	}
        	     	
        	
        }
        
             

        if (debugStageOrder) {		
	        System.err.println();
	        System.err.println("----------full stages -------------Clock:"+schedule.commonClock);
	        for(int i = 0; i<stages.length; i++) {
	        	
	        	StringBuilder target = new StringBuilder();
	    		target.append(i+" full stages "+stages[i].getClass().getSimpleName()+":"+stages[i].stageId);
	    		target.append("  inputs:");
	    		GraphManager.appendInputs(graphManager, target, stages[i]);
	    		target.append(" outputs:");
	    		GraphManager.appendOutputs(graphManager, target, stages[i]);
	    		        		
	    		System.err.println("   "+target);
	        	
	        }
        }
        
        
        
    }

    //NOTE: this can be toggled at runtime as needed.
    public void setLowLatencyEnforced(boolean value) {
    	lowLatencyEnforced = value;
    }
    
    public ScriptedNonThreadScheduler(GraphManager graphManager) {
    	this(graphManager, false);
    }
    
    public ScriptedNonThreadScheduler(GraphManager graphManager, boolean reverseOrder) {
        super(graphManager);
        this.graphManager = graphManager;
        
        
        PronghornStage[] temp = null;

	    PronghornStage[][] orderedStages = ScriptedFixedThreadsScheduler.buildStageGroups(graphManager, 1, true);
	   
	    int i = orderedStages.length;
	    while (--i>=0) {
	    	if (null != orderedStages[i]) {
	    		
	    		if (null == temp) {
	    			temp = orderedStages[i];
	    		} else {
	    			logger.trace("warning had to roll up, check the hard limit on threads");
	    			
	    			//roll up any stages
	    			PronghornStage[] additional = orderedStages[i];
	    			PronghornStage[] newList = new PronghornStage[temp.length+additional.length];
	    			
	    			System.arraycopy(temp, 0, newList, 0, temp.length);
	    			System.arraycopy(additional, 0, newList, temp.length, additional.length);
	    			
	    			temp = newList;
	    			
	    		}
	    	} 
	    }
	    
	    this.stages = temp;
        buildSchedule(-1, graphManager, stages, reverseOrder);
    }

    public ScriptedNonThreadScheduler(int scheduleId, GraphManager graphManager, boolean reverseOrder, PronghornStage[] stages, String name) {
        super(graphManager);
        this.stages = stages;
        this.graphManager = graphManager;
        this.name = name;

        buildSchedule(scheduleId, graphManager, stages, reverseOrder);
    }

    public ScriptedNonThreadScheduler(int scheduleId, GraphManager graphManager, boolean reverseOrder, PronghornStage[] stages, String name, boolean isInLargerScheduler) {
        super(graphManager);
                
        this.stages = stages;
        this.graphManager = graphManager;
        this.name = name;

        buildSchedule(scheduleId, graphManager, stages, reverseOrder);
    }

    
    public ScriptedSchedule schedule() {
    	return schedule;
    }
    
    RunningStdDev stdDevRate = null;

    public RunningStdDev stdDevRate() {
        if (null == stdDevRate) {
            stdDevRate = new RunningStdDev();
            int i = stages.length;
            while (--i >= 0) {
                Number n = (Number) GraphManager.getNota(graphManager, stages[i].stageId, GraphManager.SCHEDULE_RATE,
                                                         1_200);
                RunningStdDev.sample(stdDevRate, n.doubleValue());
            }

        }
        return stdDevRate;
    }

    public void checkForException() {
        if (firstException != null) {
            throw new RuntimeException(firstException);
        }
    }

    public String name() {
        return name;
    }

    @Override
    public void startup() {
    	shutdownRequested = new AtomicBoolean(false);
    	if (null==stages) {
    		return;
    	}
    	

        final int stageCount = stages.length;


        //TODO: we need to re-order the stages to ensure we run these in order?  This will be important.

        //System.err.println("beging stage startup "+this.hashCode());
        startupAllStages(stageCount);
        //System.err.println("done stage startup "+this.hashCode());

        int i;
        producersIdx = buildProducersList(0, 0, graphManager, stages);
        producerInputPipes = buildProducersPipes(0, 0, 1, producersIdx, stages, graphManager);
        producerInputPipeHeads = new long[producerInputPipes.length];

        inputPipes = buildInputPipes(0, 0, 1, stages, graphManager);
        inputPipeHeads = new long[inputPipes.length];

        syncInputHeadValues(producerInputPipes, producerInputPipeHeads);
        syncInputHeadValues(inputPipes, inputPipeHeads);

    }

    private static void syncInputHeadValues(Pipe[] pipes, long[] heads) {
        int i = pipes.length;
        while (--i >= 0) {//keep these so we know that it has changed and there is new content
            heads[i] = Pipe.headPosition(pipes[i]);
        }
    }

    private static boolean isSyncInputHeadValues(Pipe[] pipes, long[] heads) {
        int i = pipes.length;
        while (--i >= 0) {//keep these so we know that it has changed and there is new content
            if (heads[i] != Pipe.headPosition(pipes[i])) {
                return false;
            }
        }
        return true;
    }

    private static int[] buildProducersList(int count, int idx, final GraphManager graphManager, PronghornStage[] stages) {

        //skip over the non producers
        while (idx < stages.length) {

            if (null != GraphManager.getNota(graphManager, stages[idx].stageId, GraphManager.PRODUCER, null) ||
                    (0 == GraphManager.getInputPipeCount(graphManager, stages[idx]))) {
                int[] result = buildProducersList(count + 1, idx + 1, graphManager, stages);
                result[count] = idx;
                return result;
            }

            idx++;
        }

        return new int[count];

    }

    private static Pipe[] buildProducersPipes(int count, int indexesIdx, int outputIdx, final int[] indexes, final PronghornStage[] stages, final GraphManager graphManager) {

        while (indexesIdx < indexes.length) {

            int outputCount = GraphManager.getOutputPipeCount(graphManager, stages[indexes[indexesIdx]].stageId);
            while (outputIdx <= outputCount) {

                Pipe pipe = GraphManager.getOutputPipe(graphManager, stages[indexes[indexesIdx]], outputIdx);

                //is the consumer of this pipe inside the graph?
                int consumerId = GraphManager.getRingConsumerId(graphManager, pipe.id);

                int k = stages.length;
                while (--k >= 0) {
                    if (stages[k].stageId == consumerId) {

                        Pipe[] result = buildProducersPipes(count + 1, indexesIdx, outputIdx + 1, indexes, stages,
                                                            graphManager);
                        result[count] = pipe;
                        return result;

                    }
                }
                outputIdx++;
            }
            outputIdx = 1;
            indexesIdx++;
        }
        return new Pipe[count];

    }

    private static Pipe[] buildInputPipes(int count, int stageIdx, int inputIdx, final PronghornStage[] stages, final GraphManager graphManager) {

        while (stageIdx < stages.length) {

            int inputCount = GraphManager.getInputPipeCount(graphManager, stages[stageIdx]);
            while (inputIdx <= inputCount) {

                Pipe pipe = GraphManager.getInputPipe(graphManager, stages[stageIdx], inputIdx);

                int producerId = GraphManager.getRingProducerId(graphManager, pipe.id);

                boolean isFromOutside = true;
                int k = stages.length;
                while (--k >= 0) {
                    if (stages[k].stageId == producerId) {
                        isFromOutside = false;
                        break;
                    }
                }
                if (isFromOutside) {
                    Pipe[] result = buildInputPipes(count + 1, stageIdx, inputIdx + 1, stages, graphManager);
                    result[count] = pipe;
                    return result;

                }

                inputIdx++;
            }
            inputIdx = 1;
            stageIdx++;
        }
        return new Pipe[count];

    }


    /**
     * Stages have unknown dependencies based on their own internal locks and the pipe usages.  As a result we do not
     * know the right order for starting them. 
     */
    private void startupAllStages(final int stageCount) {

        int j;
        boolean isAnyRateLimited = false;
        //to avoid hang we must init all the inputs first
        j = stageCount;
        while (--j >= 0) {
            //this is a half init which is required when loops in the graph are discovered and we need to initialized cross dependent stages.
            if (null != stages[j]) {
                GraphManager.initInputPipesAsNeeded(graphManager, stages[j].stageId);
                isAnyRateLimited |= GraphManager.isRateLimited(graphManager, stages[j].stageId);
            }
        }

        int unInitCount = stageCount;

        while (unInitCount > 0) {

            j = stageCount;
            while (--j >= 0) {
                final PronghornStage stage = stages[j];

                if (null != stage && !GraphManager.isStageStarted(graphManager, stage.stageId)) {

                    GraphManager.initAllPipes(graphManager, stage.stageId);

                    try {
                        logger.debug("begin startup of    {}", stage);

                       // Thread thread = Thread.currentThread();
                       // new ThreadLocal<Integer>();

                        setCallerId(stage.boxedStageId);
                        stage.startup();
                        clearCallerId();

                        logger.debug("finished startup of {}", stage);

                        //client work is complete so move stage of stage to started.
                        GraphManager.setStateToStarted(graphManager, stage.stageId);
                        unInitCount--;
                    } catch (Throwable t) {
                        recordTheException(stage, t, this);
                        try {
                            if (null != stage) {
                                setCallerId(stage.boxedStageId);
                                GraphManager.shutdownStage(graphManager, stage);
                                clearCallerId();
                            }
                        } catch (Throwable tx) {
                            recordTheException(stage, tx, this);
                        } finally {
                            if (null != stage) {
                                GraphManager.setStateToShutdown(graphManager,
                                                                stage.stageId); //Must ensure marked as terminated
                            }
                        }
                        //TODO: need to halt more startups
                        //      while j< stage count must shutdown those started
                        //      then call shutdown on this scheduler
                        System.exit(-1); //this is a hack for now until the above is completed.
                        return;
                    }
                }
            }
        }


        rates = new long[stageCount + 1];
        lastRun = new long[stageCount + 1];


        int idx = stageCount;
        while (--idx >= 0) {
            final PronghornStage stage = stages[idx];

            //determine the scheduling rules
            if (null == GraphManager.getNota(graphManager, stage, GraphManager.UNSCHEDULED, null)) {

                Object value = GraphManager.getNota(graphManager, stage, GraphManager.SCHEDULE_RATE, Long.valueOf(0));
                long rate = value instanceof Number ? ((Number) value).longValue() : null == value ? 0 : Long.parseLong(
                        value.toString());

                //System.out.println("NTS schedule rate for "+stage+" is "+value);

                if (0 == rate) {
                    //DEFAULT, RUN IN TIGHT LOOP
                    rates[idx] = 0;
                    lastRun[idx] = 0;
                } else {
                    //SCHEDULE_rate, RUN EVERY rate ns
                    rates[idx] = rate;
                    if (rate > maxRate) {
                        maxRate = rate;
                    }
                    lastRun[idx] = 0;
                }
            } else {
                //UNSCHEDULED, NEVER RUN
                rates[idx] = -1;
                lastRun[idx] = 0;
            }
        }

    }

    public long nextRun() {
        return nextRun;
    }

    // Pre-allocate startup information.
    // this value is continues to keep time across calls to run.
    private long blockStartTime = System.nanoTime();
    
    private int platformThresholdForSleep = 0;

	
    
    @Override
    public void run() {

    	assert(null != shutdownRequested) : "startup() must be called before run.";
        
    	playScript(graphManager, stages, schedule.script, recordTime);
				
    }

	private void playScript(GraphManager localGM, PronghornStage[] localStages, 
			                int[] script, final boolean recordTime) {

		//TODO: before running the full group must compute part of the DynamicDisableSubGraph...
		
		
		final int length = script.length;
		int scheduleIdx = 0;

        //play the script
		while (scheduleIdx < length) {
    		        	   
			if (enabled[scheduleIdx] > 0) {
				//skip over this one, it is disabled due to lack of use.
				scheduleIdx += enabled[scheduleIdx];
			} else {			
				
	            // We need to wait between scheduler blocks, or else
	            // we'll just burn through the entire schedule nearly instantaneously.
	            //
	            // If we're still waiting, we need to wait until its time to run again.
	        	final long wait = blockStartTime - System.nanoTime(); 
	            if (wait > 0) {
	            	if (wait > 1_000_000_000) {//1sec
	            		blockStartTime = System.nanoTime() + schedule.commonClock;
	            		logger.info("warning there may be an issue with the local clock");
	            		break;
	            	}
	            	try {
	            		platformThresholdForSleep = waitForBatch(wait, platformThresholdForSleep, blockStartTime);
	            	} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						break;
					}
	            }
				scheduleIdx = runBlock(scheduleIdx, script, localStages, localGM, recordTime);
			}
        }
	}

	private int waitForBatch(long wait, int platformThresholdForSleep, long blockStartTime) throws InterruptedException {
		//some platforms will not sleep long enough so the spin yield is below 
		//logger.info("sleep: {} common clock {}",wait,schedule.commonClock);
		/////////////////////////////
		//due to JVM limitations sleep should not be called for "short" delays
		//this is because it will take a lot longer to return and may round
		//up the time to the nearest MS
		/////////////////////////////
		if (wait > platformThresholdForSleep) {
			Thread.sleep(wait/1_000_000,(int)(wait%1_000_000));
			
			//we did the sleep but we waited far too long 
			long now = System.nanoTime();
			if ((now-blockStartTime) > (wait<<3)) {
				platformThresholdForSleep = storeNewThreshold(wait);
			} else {
				//did not wait long enough
				if ((blockStartTime-now)>0) {
					platformThresholdForSleep = storeNewThreshold(wait);
				}
			}
		} else {			
			//TODO: if we have a "short term" issue we want to check again so set a timer here
			//      and reset platformThresholdForSleep back down to zero if we are peging CPU
		}
				
		
		long dif;	
		if (lowLatencyEnforced) {			
			while ((dif = (blockStartTime-System.nanoTime())) > NS_OPERATOR_FLOOR) {
				if (dif>4_000) {
					LockSupport.parkNanos(dif);
				} else {
					Thread.yield();
				}
			}
		} else {
			sumWait += (blockStartTime-System.nanoTime());
			while (sumWait>1000000) {
				Thread.yield();
				Thread.sleep(1);//ensure we are not running too fast
				sumWait-=1000000;
			}
		}
		
		return platformThresholdForSleep;
	}

	private int storeNewThreshold(long wait) {
		int platformThresholdForSleep;
		platformThresholdForSleep = (int)Math.min( wait*2, 20_000_000);//20 MS max value
		//logger.trace("new sleep threshold {}", platformThresholdForSleep);
		return platformThresholdForSleep;
	}

	long sumWait = 0;
	
	private int runBlock(int scheduleIdx, int[] script, 
			             PronghornStage[] localStage,
			             GraphManager gm, final boolean recordTime) {
		
		//long x = blockStartTime;
		
		
		boolean shutDownRequestedHere = false;
		int inProgressIdx;
		// Once we're done waiting for a block, we need to execute it!
		top:
		do {
		    // Identify the index of the block we're starting with.
			inProgressIdx = script[scheduleIdx++];
		    
		    // If it isn't a block-end (-1), run it!
		    if (inProgressIdx >= 0) {

		    	final long start = recordTime ? System.nanoTime() : 0;
		    	
		    	if (!run(gm, localStage[inProgressIdx], this)) {
					shutDownRequestedHere = true;
				}
		        
				if (recordTime) {
					final long now = System.nanoTime();		        
		        	long duration = now-start;
		        	
		        	//the duration is too large vs period
		        	// so either period is too small or duration is too large.
		        	
					if (!GraphManager.accumRunTimeNS(gm, localStage[inProgressIdx].stageId, duration, now)){
						if (lowLatencyEnforced) {
							lowLatencyEnforced = false;
							logger.warn("This platform is unable to run in low latency mode due to OS or hardware limitations. Parts of the graph have now been switched to high volume mode.");
						}
					}
					
				}
		    } else {
		    	break;
		    }
		} while (true);

		// Update the block start time.
		blockStartTime += schedule.commonClock;
		
		//if we have long running cycles which are longer than then common clock
		//bump up the time so it does not keep falling further behind.  this allows
		//the script to stop on the first "fast" cycle instead of taking multiple
		//runs at the script to catch up when this will not help the performance.
		long now = System.nanoTime();
		if (blockStartTime <= now) {
			blockStartTime = now;
		}
        		
		// If a shutdown is triggered in any way, shutdown and halt this scheduler.
		if (!(shutDownRequestedHere || shutdownRequested.get())) {
			return scheduleIdx;
		} else {
			if (!shutdownRequested.get()) {
				shutdown();
			}
		    return Integer.MAX_VALUE;
		}
	}

	public boolean isContentForStage(PronghornStage stage) {
		int inC = GraphManager.getInputPipeCount(graphManager, stage.stageId);
		for(int k = 1; k <= inC; k++) {
			if (Pipe.contentRemaining((Pipe<?>)
					GraphManager.getInputPipe(graphManager, stage.stageId, k)
					) != 0) {
				return true;
			}
		}
		return false;
	}

    private static boolean run(GraphManager graphManager, 
    		                   PronghornStage stage, 
    		                   ScriptedNonThreadScheduler that) {
        try {
            if (!GraphManager.isStageShuttingDown(that.stateArray, stage.stageId)) {

                if (debugNonReturningStages) {
                    logger.info("begin run {}", stage);///for debug of hang
                }
                that.setCallerId(stage.boxedStageId);
                stage.run();
                that.clearCallerId();

                if (debugNonReturningStages) {
                    logger.info("end run {}", stage);
                }
                return true;
            } else {
                if (!GraphManager.isStageTerminated(graphManager, stage.stageId)) {
                    GraphManager.shutdownStage(graphManager, stage);
                    GraphManager.setStateToShutdown(graphManager, stage.stageId);
                }
                return false;
            }
        } catch (Throwable t) {
            recordTheException(stage, t, that);
            throw t;
        }

    }

    private Object key = "key";
    
    @Override
    public void shutdown() {
    	
        if (null!=stages && shutdownRequested.compareAndSet(false, true)) {

        	synchronized(key) {
                boolean debug = false;
                if (debug) {	
        	        System.err.println();
        	        System.err.println("----------full stages ------------- clock:"+schedule.commonClock);
        	        for(int i = 0; i<stages.length; i++) {
        	        	
        	        	StringBuilder target = new StringBuilder();
        	    		target.append("full stages "+stages[i].getClass().getSimpleName()+":"+stages[i].stageId);
        	    		target.append("  inputs:");
        	    		GraphManager.appendInputs(graphManager, target, stages[i]);
        	    		target.append(" outputs:");
        	    		GraphManager.appendOutputs(graphManager, target, stages[i]);
        	    		        		
        	    		System.err.println("   "+target);
        	        	
        	        }
                }
            }
        	
        	
        	
            int s = stages.length;
            while (--s >= 0) {
                //ensure every non terminated stage gets shutdown called.
                if (null != stages[s] && !GraphManager.isStageTerminated(graphManager, stages[s].stageId)) {
                    GraphManager.shutdownStage(graphManager, stages[s]);
                    GraphManager.setStateToShutdown(graphManager, stages[s].stageId);
                    //System.err.println("terminated "+stages[s]+"  "+GraphManager.isStageTerminated(graphManager, stages[s].stageId));
                }
            }

            PronghornStage temp = lastRunStage;
            if (null != temp) {
                logger.info("ERROR: this stage was called but never returned {}", temp);
            }
        }

    }

    public static boolean isShutdownRequested(ScriptedNonThreadScheduler nts) {
        return nts.shutdownRequested.get();
    }

    @Override
    public void awaitTermination(long timeout, TimeUnit unit, Runnable clean, Runnable dirty) {
        if (awaitTermination(timeout, unit)) {
            clean.run();
        } else {
            dirty.run();
        }
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {

        if (!shutdownRequested.get()) {
            throw new UnsupportedOperationException("call shutdown before awaitTerminination");
        }
        long limit = System.nanoTime() + unit.toNanos(timeout);

        if (isRunning.get() != 2) {
            //wait until we get shutdown or timeout.
            while (!isRunning.compareAndSet(0, 2)) {
                Thread.yield();
                if (System.nanoTime() > limit) {
                    return false;
                }
            }
        }

        int s = stages.length;
        while (--s >= 0) {
            PronghornStage stage = stages[s];

            if (null != stage && !GraphManager.isStageTerminated(graphManager, stage.stageId)) {
                GraphManager.shutdownStage(graphManager, stage);
                GraphManager.setStateToShutdown(graphManager, stage.stageId);
            }
        }

        return true;

    }

    @Override
    public boolean TerminateNow() {
        shutdown();
        return true;
    }

    private static void recordTheException(final PronghornStage stage, Throwable t, ScriptedNonThreadScheduler that) {
        synchronized (that) {
            if (null == that.firstException) {
                that.firstException = t;
            }
        }

        GraphManager.reportError(that.graphManager, stage, t, logger);
    }
    
    

	public static int[] buildSkipScript(ScriptedSchedule schedule, 
			                            GraphManager gm, 
			                            PronghornStage[] stages,
			                            int[] script) {
		
		int[] skipScript = new int[script.length];
		int lastPointIndex = 0;
		
		for(int idx = 0; idx<script.length; idx++) {
			if (script[idx] != -1) {
				int stageId = stages[schedule.script[idx]].stageId;

				final int inC1 = GraphManager.getInputPipeCount(gm, stageId);
				if ((inC1 == 0) || (GraphManager.hasNota(gm, stageId, GraphManager.PRODUCER))) {
					//producer, all producers must always be run.
					skipScript[idx] = -2;
					lastPointIndex = idx+1; //this starts a new run from here.
					continue;
				} else {
					for(int k = 1; k <= inC1; k++) {
						int id = GraphManager.getInputPipe(gm, stageId, k).id;
						//this id MUST be found as one of the previous outs
						//if not found this is a new point
						if (!isInputLocal(lastPointIndex, idx, gm, stages, script, id)) {
							lastPointIndex = idx;
						}
					}
				}
				
				//count up how long this run is at the head position of the run
				if (lastPointIndex!=-1) {
					skipScript[lastPointIndex]++;
				}
				
			} else {
				skipScript[idx] = -1;
				lastPointIndex = idx+1;
			}
			
			
		}
		return skipScript;
	}

	private static boolean isInputLocal(int startIdx,
										int stopIdx, 
			                            GraphManager gm, 
			                            PronghornStage[] stages, 
			                            int[] script,
			                            int goalId) {
		//scan for an output which matches this goal Id
		
		for(int i = startIdx; i<=stopIdx; i++) {
			int stageId = stages[script[i]].stageId;
			int outC = GraphManager.getOutputPipeCount(gm, stageId);
			for(int k = 1; k <= outC; k++) {
				if (goalId == GraphManager.getOutputPipe(gm, stageId, k).id) {
					return true;
				}
			}
		}
		return false;
	}
	
}
