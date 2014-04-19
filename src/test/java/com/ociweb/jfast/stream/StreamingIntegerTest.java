//Copyright 2013, Nathan Tippy
//See LICENSE file for BSD license details.
//Send support requests to http://www.ociweb.com/contact
package com.ociweb.jfast.stream;

import static org.junit.Assert.assertEquals;

import org.junit.AfterClass;
import org.junit.Test;

import com.ociweb.jfast.field.OperatorMask;
import com.ociweb.jfast.field.TokenBuilder;
import com.ociweb.jfast.field.TypeMask;
import com.ociweb.jfast.loader.DictionaryFactory;
import com.ociweb.jfast.loader.TemplateCatalog;
import com.ociweb.jfast.primitive.PrimitiveReader;
import com.ociweb.jfast.primitive.PrimitiveWriter;
import com.ociweb.jfast.primitive.adapter.FASTInputByteArray;
import com.ociweb.jfast.primitive.adapter.FASTOutputByteArray;



public class StreamingIntegerTest extends BaseStreamingTest {
	
	final int groupToken 	  = TokenBuilder.buildToken(TypeMask.Group,maxMPapBytes>0?OperatorMask.Group_Bit_PMap:0,maxMPapBytes, TokenBuilder.MASK_ABSENT_DEFAULT);
	final int[] testData     = buildTestDataUnsigned(fields);
	final int   testConst    = 0; //must be zero because Dictionary was not init with anything else
	boolean sendNulls        = true;
		
	FASTOutputByteArray output;
	PrimitiveWriter pw;
	
	FASTInputByteArray input;
	PrimitiveReader pr;

	int MASK = 0xF;
	
	@AfterClass
	public static void cleanup() {
		System.gc();
	}
	
	@Test
	public void integerUnsignedTest() {
		int[] types = new int[] {
                  TypeMask.IntegerUnsigned,
		    	  TypeMask.IntegerUnsignedOptional,
				  };
		
		int[] operators = new int[] {
                OperatorMask.Field_None,  //no need for pmap
                OperatorMask.Field_Delta, //no need for pmap
                OperatorMask.Field_Copy,
                OperatorMask.Field_Increment,
                OperatorMask.Field_Constant, 
                OperatorMask.Field_Default
                };
				
		tester(types, operators, "UnsignedInteger",0 ,0);
	}
	
	@Test
	public void integerSignedTest() {
		int[] types = new int[] {
                  TypeMask.IntegerSigned,
				  TypeMask.IntegerSignedOptional,
				  };
		
		int[] operators = new int[] {
                OperatorMask.Field_None,  //no need for pmap
                OperatorMask.Field_Delta, //no need for pmap
                OperatorMask.Field_Copy,
                OperatorMask.Field_Increment,
                OperatorMask.Field_Constant, 
                OperatorMask.Field_Default
                };
		tester(types, operators, "SignedInteger",0 ,0);
	}

	@Test
	public void integerFullTest() {
		int[] types = new int[] {
                  TypeMask.IntegerSigned,
				  TypeMask.IntegerSignedOptional,
                  TypeMask.IntegerUnsigned,
		    	  TypeMask.IntegerUnsignedOptional,
				  };
		
		int[] operators = new int[] {
                OperatorMask.Field_None,  //no need for pmap
                OperatorMask.Field_Delta, //no need for pmap
                OperatorMask.Field_Copy,
                OperatorMask.Field_Increment,
                OperatorMask.Field_Constant, 
                OperatorMask.Field_Default
                };
		int repeat = 1;//bump up for profiler
		while (--repeat>=0) {
			tester(types, operators, "SignedInteger",0 ,0);
		}
	}

	
	
	
	@Override
	protected long timeWriteLoop(int fields, int fieldsPerGroup, int maxMPapBytes, int operationIters,
			int[] tokenLookup, DictionaryFactory dcr) {
				
		FASTWriterDispatch fw = new FASTWriterDispatch(pw, dcr, 100, 64, 64, 8, 8, null, 3, new int[0][0],null,64);
		
		long start = System.nanoTime();
		assert(operationIters>3) : "must allow operations to have 3 data points but only had "+operationIters;
				
		int i = operationIters;
		int g = fieldsPerGroup;
		fw.openGroup(groupToken, maxMPapBytes);
		
		while (--i>=0) {
			int f = fields;
		
			while (--f>=0) {
				
				int token = tokenLookup[f]; 
							
				if (((token>>TokenBuilder.SHIFT_OPER)&TokenBuilder.MASK_OPER)==OperatorMask.Field_Constant) {
					
					//special test with constant value.
					if (sendNulls && ((i&MASK)==0) && TokenBuilder.isOptional(token)) {
						fw.write(token);//nothing
					} else {
						fw.writeInteger(token, testConst); 
					}
				} else {
					if (sendNulls && ((f&MASK)==0) && TokenBuilder.isOptional(token)) {
						//System.err.println("write null");
						fw.write(token);
					} else {
						fw.writeInteger(token, testData[f]); 
					}
				}
							
				g = groupManagementWrite(fieldsPerGroup, fw, i, g, groupToken, groupToken, f, maxMPapBytes);				
			}			
		}
		if ( ((fieldsPerGroup*fields)%fieldsPerGroup) == 0  ) {
			fw.closeGroup(groupToken|(OperatorMask.Group_Bit_Close<<TokenBuilder.SHIFT_OPER));
		}
		fw.flush();
				
		return System.nanoTime() - start;
	}
	
	@Override
	protected long timeReadLoop(int fields, int fieldsPerGroup, int maxMPapBytes, 
			                      int operationIters, int[] tokenLookup, DictionaryFactory dcr) {
		
		FASTReaderDispatch fr = new FASTReaderDispatch(pr, dcr, 3, new int[0][0], 0, 0, 4, 4, null,64, 8, 7);
		
		long start = System.nanoTime();
		if (operationIters<3) {
			throw new UnsupportedOperationException("must allow operations to have 3 data points but only had "+operationIters);
		}
			
		int i = operationIters;
		int g = fieldsPerGroup;
		
		fr.openGroup(groupToken, maxMPapBytes);
		
		while (--i>=0) {
			int f = fields;
			
			while (--f>=0) {
				
				int token = tokenLookup[f]; 	
														
				if (((token>>TokenBuilder.SHIFT_OPER)&TokenBuilder.MASK_OPER)==OperatorMask.Field_Constant) {
					if (sendNulls && (i&MASK)==0 && TokenBuilder.isOptional(token)) {
			     		int value = fr.readInt(tokenLookup[f]);
						if (TemplateCatalog.DEFAULT_CLIENT_SIDE_ABSENT_VALUE_INT!=value) {
							assertEquals(TemplateCatalog.DEFAULT_CLIENT_SIDE_ABSENT_VALUE_INT, value);
						}
					} else { 
						int value = fr.readInt(tokenLookup[f]);
						if (testConst!=value) {
							System.err.println(TokenBuilder.tokenToString(tokenLookup[f]));
							assertEquals(testConst, value);
						}
					}
				
				} else {	
				
					if (sendNulls && (f&MASK)==0 && TokenBuilder.isOptional(token)) {
			     		int value = fr.readInt(tokenLookup[f]);
						if (TemplateCatalog.DEFAULT_CLIENT_SIDE_ABSENT_VALUE_INT!=value) {
							assertEquals(TemplateCatalog.DEFAULT_CLIENT_SIDE_ABSENT_VALUE_INT, value);
						}
					} else { 
						int value = fr.readInt(tokenLookup[f]);
						if (testData[f]!=value) {
							System.err.println(TokenBuilder.tokenToString(tokenLookup[f]));
							assertEquals(testData[f], value);
						}
					}
				}
				
				g = groupManagementRead(fieldsPerGroup, fr, i, g, groupToken, f, maxMPapBytes);				
			}			
		}
		if ( ((fieldsPerGroup*fields)%fieldsPerGroup) == 0  ) {
			fr.closeGroup(groupToken|(OperatorMask.Group_Bit_Close<<TokenBuilder.SHIFT_OPER));
		}
			
		long duration = System.nanoTime() - start;
		return duration;
	}

	public long totalWritten() {
		return pw.totalWritten();
	}
	
	protected void resetOutputWriter() {
		output.reset();
		pw.reset();
	}

	protected void buildOutputWriter(int maxGroupCount, byte[] writeBuffer) {
		output = new FASTOutputByteArray(writeBuffer);
		pw = new PrimitiveWriter(4096, output, maxGroupCount, false);
	}


	protected long totalRead() {
		return pr.totalRead();
	}
	
	protected void resetInputReader() {
		input.reset();
		pr.reset();
	}

	protected void buildInputReader(int maxGroupCount, byte[] writtenData, int writtenBytes) {
		input = new FASTInputByteArray(writtenData,writtenBytes);
		pr = new PrimitiveReader(4096, input, maxGroupCount*10);
	}

	
}