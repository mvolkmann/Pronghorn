package com.ociweb.jfast.stream;

import com.ociweb.jfast.field.LocalHeap;
import com.ociweb.jfast.field.LocalHeap;
import com.ociweb.jfast.field.TokenBuilder;
import com.ociweb.jfast.loader.DictionaryFactory;
import com.ociweb.jfast.loader.TemplateCatalogConfig;
import com.ociweb.jfast.primitive.PrimitiveReader;

public abstract class FASTDecoder{
    
    //active state, TODO: C, minimize or remove these. 
    
    //all constants always skipped.
    //decimals as well??
    
    private final int[] templateStartIdx; //These constants can be remvoed
    private final int[] templateLimitIdx;//These constants can be remvoed
    

    //runtime count of sequence lengths
    public int sequenceCountStackHead = -1;
    public final int[] sequenceCountStack;
    
    //private ring buffers for writing content into
    public final RingBuffers ringBuffers;
    
    //dictionary data
    protected final long[] rLongDictionary; //final array with constant references
    protected final int[] rIntDictionary; //final array with constant references
    protected final LocalHeap byteHeap;
    
    public int activeScriptCursor=-1; //needed by generated code to hold state between calls.
    public int ringBufferIdx= -1; //must hold return value from beginning of fragment to the end.
    public int templateId=-1; //must hold between read (wait for space on queue) and write of templateId
    public int preambleA=0; //must hold between read (wait for space on queue) and write (if it happens)
    public int preambleB=0; //must hold between read (wait for space on queue) and write (if it happens)
            
   
        
    public FASTDecoder(TemplateCatalogConfig catalog) {
        this(catalog.dictionaryFactory(), catalog.getMaxGroupDepth(), computePMapStackInBytes(catalog), 
             catalog.getTemplateStartIdx(), catalog.getTemplateLimitIdx(),
             catalog.maxTemplatePMapSize(), catalog.clientConfig().getPreableBytes(), catalog.ringBuffers());
    }
    
    private static int computePMapStackInBytes(TemplateCatalogConfig catalog) {
        return 2 + ((Math.max(
                catalog.maxTemplatePMapSize(), catalog.maxNonTemplatePMapSize()) + 2) * catalog.getMaxGroupDepth());
    }
    
            
    private FASTDecoder(DictionaryFactory dcr, int maxNestedGroupDepth, int maxPMapCountInBytes,
            int[] templateStartIdx, int[] templateLimitIdx,
            int maxTemplatePMapSize, int preambleDataLength, RingBuffers ringBuffers) {

        this.byteHeap = dcr.byteDictionary();
        
        this.sequenceCountStack = new int[maxNestedGroupDepth];
        this.rIntDictionary = dcr.integerDictionary();
        this.rLongDictionary = dcr.longDictionary();
        
        this.templateStartIdx = templateStartIdx;
        this.templateLimitIdx = templateLimitIdx;
        
        this.ringBuffers = ringBuffers;
        
        assert (rIntDictionary.length < TokenBuilder.MAX_INSTANCE);
        assert (TokenBuilder.isPowerOfTwo(rIntDictionary.length));
        assert (rLongDictionary.length < TokenBuilder.MAX_INSTANCE);
        assert (TokenBuilder.isPowerOfTwo(rLongDictionary.length));
    }
    
    
    public void reset(DictionaryFactory dictionaryFactory) {
                
        // clear all previous values to un-set
        dictionaryFactory.reset(rIntDictionary); //TODO: A, need generator for this
        dictionaryFactory.reset(rLongDictionary); //TODO: A, need generator fo this
                
        
        if (null!=byteHeap) {
            LocalHeap.reset(byteHeap);
        }
        sequenceCountStackHead = -1;
        
        RingBuffers.reset(ringBuffers);        

    }

    public abstract int decode(PrimitiveReader reader);
        
  
    

    public int activeScriptLimit; //TODO: B, remvoe this once limit is removed from iterprister after stack is used for exit flag.
    
    //TODO: A, remove or change to static.
//    Exception in thread "pool-203-thread-1" java.lang.ArrayIndexOutOfBoundsException: -1
//    at com.ociweb.jfast.stream.FASTDecoder.requiredBufferSpace2(FASTDecoder.java:101)
//    at com.ociweb.jfast.generator.FASTReaderGeneratedDispatch.t_002(FASTReaderGeneratedDispatch.java:44)
//    at com.ociweb.jfast.generator.FASTReaderGeneratedDispatch.beginMessage(FASTReaderGeneratedDispatch.java:34)
//    at com.ociweb.jfast.generator.FASTReaderGeneratedDispatch.decode(FASTReaderGeneratedDispatch.java:470)
    public static int requiredBufferSpace2(FASTDecoder decoder, int templateId) {
        
        decoder.activeScriptCursor = decoder.templateStartIdx[templateId];//set location for the generated code state.
        decoder.activeScriptLimit = decoder.templateLimitIdx[templateId];

        return (decoder.activeScriptLimit - decoder.activeScriptCursor) << 2;        
        
    }
    

}
