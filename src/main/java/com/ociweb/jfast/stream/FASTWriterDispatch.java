//Copyright 2013, Nathan Tippy
//See LICENSE file for BSD license details.
//Send support requests to http://www.ociweb.com/contact
package com.ociweb.jfast.stream;

import java.nio.ByteBuffer;

import com.ociweb.jfast.field.FieldWriterBytes;
import com.ociweb.jfast.field.FieldWriterText;
import com.ociweb.jfast.field.FieldWriterDecimal;
import com.ociweb.jfast.field.FieldWriterInteger;
import com.ociweb.jfast.field.FieldWriterLong;
import com.ociweb.jfast.field.OperatorMask;
import com.ociweb.jfast.field.TokenBuilder;
import com.ociweb.jfast.field.TypeMask;
import com.ociweb.jfast.loader.DictionaryFactory;
import com.ociweb.jfast.loader.TemplateCatalog;
import com.ociweb.jfast.primitive.PrimitiveWriter;

//May drop interface if this causes a performance problem from virtual table 
public final class FASTWriterDispatch {

    private int templateStackHead = 0;
    private final int[] templateStack;

    private final PrimitiveWriter writer;

    private final FieldWriterInteger writerInteger;
    private final FieldWriterLong writerLong;
    public final FieldWriterDecimal writerDecimal;
    private final FieldWriterText writerChar;
    private final FieldWriterBytes writerBytes;

    final int nonTemplatePMapSize;

    private int readFromIdx = -1;

    private final DictionaryFactory dictionaryFactory;
    private final FASTRingBuffer queue;
    private final int[][] dictionaryMembers;

    private final int[] sequenceCountStack;
    private int sequenceCountStackHead = -1;
    private boolean isFirstSequenceItem = false;
    private boolean isSkippedSequence = false;
    private DispatchObserver observer;
    int activeScriptCursor;
    int activeScriptLimit;
    final int[] fullScript;

    private RingCharSequence ringCharSequence = new RingCharSequence();

    public FASTWriterDispatch(PrimitiveWriter writer, DictionaryFactory dcr, int maxTemplates, int maxCharSize,
            int maxBytesSize, int gapChars, int gapBytes, FASTRingBuffer queue, int nonTemplatePMapSize,
            int[][] dictionaryMembers, int[] fullScript, int maxNestedGroupDepth) {

        this.fullScript = fullScript;
        this.writer = writer;
        this.dictionaryFactory = dcr;
        this.nonTemplatePMapSize = nonTemplatePMapSize;

        this.sequenceCountStack = new int[maxNestedGroupDepth];

        this.writerInteger = new FieldWriterInteger(writer, dcr.integerDictionary(), dcr.integerDictionary());
        this.writerLong = new FieldWriterLong(writer, dcr.longDictionary(), dcr.longDictionary());
        //
        this.writerDecimal = new FieldWriterDecimal(writer, dcr.decimalExponentDictionary(),
                dcr.decimalExponentDictionary(), dcr.decimalMantissaDictionary(), dcr.decimalMantissaDictionary());
        this.writerChar = new FieldWriterText(writer, dcr.charDictionary(maxCharSize, gapChars));
        this.writerBytes = new FieldWriterBytes(writer, dcr.byteDictionary(maxBytesSize, gapBytes));

        this.templateStack = new int[maxTemplates];
        this.queue = queue;
        this.dictionaryMembers = dictionaryMembers;
    }

    public void setDispatchObserver(DispatchObserver observer) {
        this.observer = observer;
    }

    /**
     * Write null value, must only be used if the field id is one of optional
     * type.
     */
    public void write(int token) {

        // only optional field types can use this method.
        assert (0 != (token & (1 << TokenBuilder.SHIFT_TYPE))); // TODO: T, in
                                                                // testing
                                                                // assert(failOnBadArg())

        // select on type, each dictionary will need to remember the null was
        // written
        if (0 == (token & (8 << TokenBuilder.SHIFT_TYPE))) {
            // int long
            if (0 == (token & (4 << TokenBuilder.SHIFT_TYPE))) {
                // int
                writerInteger.writeNull(token);
            } else {
                // long
                writerLong.writeNull(token);
            }
        } else {
            // text decimal bytes
            if (0 == (token & (4 << TokenBuilder.SHIFT_TYPE))) {
                // text
                writerChar.writeNull(token);
            } else {
                // decimal bytes
                if (0 == (token & (2 << TokenBuilder.SHIFT_TYPE))) {
                    // decimal
                    writerDecimal.writeNull(token);
                } else {
                    // byte
                    writerBytes.writeNull(token);
                }
            }
        }

    }

    /**
     * Method for writing signed unsigned and/or optional longs. To write the
     * "null" or absence of a value use void write(int id)
     */
    public void writeLong(int token, long value) {

        assert (0 != (token & (4 << TokenBuilder.SHIFT_TYPE)));

        if (0 == (token & (1 << TokenBuilder.SHIFT_TYPE))) {// compiler does all
                                                            // the work.
            // not optional
            if (0 == (token & (2 << TokenBuilder.SHIFT_TYPE))) {
                acceptLongUnsigned(token, value);
            } else {
                acceptLongSigned(token, value);
            }
        } else {
            if (value == TemplateCatalog.DEFAULT_CLIENT_SIDE_ABSENT_VALUE_LONG) {
                write(token);
            } else {
                // optional
                if (0 == (token & (2 << TokenBuilder.SHIFT_TYPE))) {
                    acceptLongUnsignedOptional(token, value);
                } else {
                    acceptLongSignedOptional(token, value);
                }
            }
        }
    }

    private void acceptLongSignedOptional(int token, long value) {
        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {
            // none, constant, delta
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // none, delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    writer.writeLongSignedOptional(value);
                } else {
                    // delta
                    writerLong.writeLongSignedDeltaOptional(value, token);
                }
            } else {
                // constant
                assert (writerLong.dictionary[token & writerLong.INSTANCE_MASK] == value) : "Only the constant value from the template may be sent";
                writer.writePMapBit((byte) 1);
                // the writeNull will take care of the rest.
            }

        } else {
            // copy, default, increment
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // copy, increment
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // copy
                    writerLong.writeLongSignedCopyOptional(value, token);
                } else {
                    // increment
                    writerLong.writeLongSignedIncrementOptional(value, token);
                }
            } else {
                // default
                writerLong.writeLongSignedDefaultOptional(value, token);
            }
        }
    }

    private void acceptLongSigned(int token, long value) {

        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {
            // none, constant, delta
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // none, delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    int idx = token & writerLong.INSTANCE_MASK;

                    writer.writeLongSigned(writerLong.dictionary[idx] = value);
                } else {
                    // delta
                    writerLong.writeLongSignedDelta(value, token);
                }
            } else {
                // constant
                assert (writerLong.dictionary[token & writerLong.INSTANCE_MASK] == value) : "Only the constant value from the template may be sent";
                // nothing need be sent because constant does not use pmap and
                // the template
                // on the other receiver side will inject this value from the
                // template
            }

        } else {
            // copy, default, increment
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // copy, increment
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // copy
                    writerLong.writeLongSignedCopy(value, token);
                } else {
                    // increment
                    writerLong.writeLongSignedIncrement(value, token);
                }
            } else {
                // default
                writerLong.writeLongSignedDefault(value, token);
            }
        }

    }

    private void acceptLongUnsignedOptional(int token, long value) {
        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {
            // none, constant, delta
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // none, delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    writer.writeLongUnsigned(value + 1);
                } else {
                    // delta
                    writerLong.writeLongUnsignedDeltaOptional(value, token);
                }
            } else {
                // constant
                assert (writerLong.dictionary[token & writerLong.INSTANCE_MASK] == value) : "Only the constant value from the template may be sent";
                writer.writePMapBit((byte) 1);
                // the writeNull will take care of the rest.
            }

        } else {
            // copy, default, increment
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // copy, increment
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // copy
                    writerLong.writeLongUnsignedCopyOptional(value, token);
                } else {
                    // increment
                    writerLong.writeLongUnsignedIncrementOptional(value, token);
                }
            } else {
                // default
                writerLong.writeLongUnsignedDefaultOptional(value, token);
            }
        }
    }

    private void acceptLongUnsigned(int token, long value) {
        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {
            // none, constant, delta
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // none, delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    int idx = token & writerLong.INSTANCE_MASK;

                    writer.writeLongUnsigned(writerLong.dictionary[idx] = value);
                } else {
                    // delta
                    writerLong.writeLongUnsignedDelta(value, token);
                }
            } else {
                // constant
                assert (writerLong.dictionary[token & writerLong.INSTANCE_MASK] == value) : "Only the constant value from the template may be sent";
                // nothing need be sent because constant does not use pmap and
                // the template
                // on the other receiver side will inject this value from the
                // template
            }

        } else {
            // copy, default, increment
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // copy, increment
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // copy
                    writerLong.writeLongUnsignedCopy(value, token);
                } else {
                    // increment
                    writerLong.writeLongUnsignedIncrement(value, token);
                }
            } else {
                // default
                writerLong.writeLongUnsignedDefault(value, token);
            }
        }
    }

    /**
     * Method for writing signed unsigned and/or optional integers. To write the
     * "null" or absence of an integer use void write(int id)
     */
    public void writeInteger(int token, int value) {

        if (0 == (token & (1 << TokenBuilder.SHIFT_TYPE))) {// compiler does all
                                                            // the work.
            // not optional
            if (0 == (token & (2 << TokenBuilder.SHIFT_TYPE))) {
                acceptIntegerUnsigned(token, value);
            } else {
                acceptIntegerSigned(token, value);
            }
        } else {
            if (value == TemplateCatalog.DEFAULT_CLIENT_SIDE_ABSENT_VALUE_INT) {
                write(token);
            } else {
                // optional
                if (0 == (token & (2 << TokenBuilder.SHIFT_TYPE))) {
                    acceptIntegerUnsignedOptional(token, value);
                } else {
                    acceptIntegerSignedOptional(token, value);
                }
            }
        }
    }

    private void acceptIntegerSigned(int token, int value) {

        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {
            // none, constant, delta
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // none, delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    int idx = token & writerInteger.INSTANCE_MASK;

                    writer.writeIntegerSigned(writerInteger.dictionary[idx] = value);
                } else {
                    // delta
                    // Delta opp never uses PMAP
                    int idx = token & writerInteger.INSTANCE_MASK;

                    writer.writeIntegerSignedDelta(value, idx, writerInteger.dictionary);
                }
            } else {
                // constant
                assert (writerInteger.dictionary[token & writerInteger.INSTANCE_MASK] == value) : "Only the constant value "
                        + writerInteger.dictionary[token & writerInteger.INSTANCE_MASK]
                        + " from the template may be sent";
                // nothing need be sent because constant does not use pmap and
                // the template
                // on the other receiver side will inject this value from the
                // template
            }

        } else {
            // copy, default, increment
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // copy, increment
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // copy
                    int idx = token & writerInteger.INSTANCE_MASK;

                    writer.writeIntegerSignedCopy(value, idx, writerInteger.dictionary);
                } else {
                    // increment
                    int idx = token & writerInteger.INSTANCE_MASK;

                    writer.writeIntegerSignedIncrement(value, idx, writerInteger.dictionary);
                }
            } else {
                // default
                int idx = token & writerInteger.INSTANCE_MASK;
                int constDefault = writerInteger.dictionary[idx];

                writer.writeIntegerSignedDefault(value, idx, constDefault);
            }
        }
    }

    private void acceptIntegerUnsigned(int token, int value) {

        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {
            // none, constant, delta
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // none, delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    int idx = token & writerInteger.INSTANCE_MASK;

                    writer.writeIntegerUnsigned(writerInteger.dictionary[idx] = value);
                } else {
                    // delta
                    // Delta opp never uses PMAP
                    int idx = (token & writerInteger.INSTANCE_MASK);

                    writer.writeIntegerUnsignedDelta(value, idx, writerInteger.dictionary);
                }
            } else {
                // constant
                assert (writerInteger.dictionary[token & writerInteger.INSTANCE_MASK] == value) : "Only the constant value from the template may be sent";
                // nothing need be sent because constant does not use pmap and
                // the template
                // on the other receiver side will inject this value from the
                // template
            }

        } else {
            // copy, default, increment
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // copy, increment
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // copy
                    int idx = token & writerInteger.INSTANCE_MASK;
                    writer.writeIntegerUnsignedCopy(value, idx, writerInteger.dictionary);
                } else {
                    // increment
                    int idx = token & writerInteger.INSTANCE_MASK;

                    writer.writeIntegerUnsignedIncrement(value, idx, writerInteger.dictionary);
                }
            } else {
                // default
                int idx = token & writerInteger.INSTANCE_MASK;
                int constDefault = writerInteger.dictionary[idx];

                writer.writeIntegerUnsignedDefault(value, constDefault);
            }
        }
    }

    private void acceptIntegerSignedOptional(int token, int value) {

        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {
            // none, constant, delta
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // none, delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    writer.writeIntegerSignedOptional(value);
                } else {
                    // delta
                    int idx = token & writerInteger.INSTANCE_MASK;

                    writer.writeIntegerSignedDeltaOptional(value, idx, writerInteger.dictionary);
                }
            } else {
                // constant
                assert (writerInteger.dictionary[token & writerInteger.INSTANCE_MASK] == value) : "Only the constant value from the template may be sent";
                writer.writePMapBit((byte) 1);
                // the writeNull will take care of the rest.
            }

        } else {
            // copy, default, increment
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // copy, increment
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // copy
                    int idx = token & writerInteger.INSTANCE_MASK;

                    writer.writeIntegerSignedCopyOptional(value, idx, writerInteger.dictionary);
                } else {
                    // increment
                    int idx = token & writerInteger.INSTANCE_MASK;

                    writer.writeIntegerSignedIncrementOptional(value, idx, writerInteger.dictionary);
                }
            } else {
                // default
                int idx = token & writerInteger.INSTANCE_MASK;
                int constDefault = writerInteger.dictionary[idx];

                writer.writeIntegerSignedDefaultOptional(value, idx, constDefault);
            }
        }
    }

    private void acceptIntegerUnsignedOptional(int token, int value) {

        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {
            // none, constant, delta
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // none, delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    writer.writeIntegerUnsigned(value + 1);
                } else {
                    // delta
                    // Delta opp never uses PMAP
                    int idx = token & writerInteger.INSTANCE_MASK;

                    writer.writeIntegerUnsignedDeltaOptional(value, idx, writerInteger.dictionary);
                }
            } else {
                // constant
                assert (writerInteger.dictionary[token & writerInteger.INSTANCE_MASK] == value) : "Only the constant value from the template may be sent";
                writer.writePMapBit((byte) 1);
                // the writeNull will take care of the rest.
            }

        } else {
            // copy, default, increment
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {
                // copy, increment
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // copy
                    int idx = token & writerInteger.INSTANCE_MASK;
                    writer.writeIntegerUnsignedCopyOptional(value, idx, writerInteger.dictionary);
                } else {
                    // increment
                    int idx = token & writerInteger.INSTANCE_MASK;

                    writer.writeIntegerUnsignedIncrementOptional(value, idx, writerInteger.dictionary);
                }
            } else {
                // default
                int idx = token & writerInteger.INSTANCE_MASK;
                int constDefault = writerInteger.dictionary[idx];

                writer.writeIntegerUnsignedDefaultOptional(value, constDefault);
            }
        }
    }

    public void write(int token, byte[] value, int offset, int length) {

        assert (0 != (token & (2 << TokenBuilder.SHIFT_TYPE)));
        assert (0 != (token & (4 << TokenBuilder.SHIFT_TYPE)));
        assert (0 != (token & (8 << TokenBuilder.SHIFT_TYPE)));

        if (0 == (token & (1 << TokenBuilder.SHIFT_TYPE))) {
            acceptByteArray(token, value, offset, length);
        } else {
            acceptByteArrayOptional(token, value, offset, length);
        }
    }

    private void acceptByteArrayOptional(int token, byte[] value, int offset, int length) {
        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {// compiler does all
                                                            // the work.
            // none constant delta tail
            if (0 == (token & (6 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // none tail
                if (0 == (token & (8 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    writerBytes.writeBytesOptional(value, offset, length);
                } else {
                    // tail
                    writerBytes.writeBytesTailOptional(token, value, offset, length);
                }
            } else {
                // constant delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // constant
                    writerBytes.writeBytesConstantOptional(token);
                } else {
                    // delta
                    writerBytes.writeBytesDeltaOptional(token, value, offset, length);
                }
            }
        } else {
            // copy default
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // copy
                writerBytes.writeBytesCopyOptional(token, value, offset, length);
            } else {
                // default
                writerBytes.writeBytesDefaultOptional(token, value, offset, length);
            }
        }
    }

    private void acceptByteArray(int token, byte[] value, int offset, int length) {
        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {// compiler does all
                                                            // the work.
            // none constant delta tail
            if (0 == (token & (6 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // none tail
                if (0 == (token & (8 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    writerBytes.writeBytes(value, offset, length);
                } else {
                    // tail
                    writerBytes.writeBytesTail(token, value, offset, length);
                }
            } else {
                // constant delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // constant
                    writerBytes.writeBytesConstant(token);
                } else {
                    // delta
                    writerBytes.writeBytesDelta(token, value, offset, length);
                }
            }
        } else {
            // copy default
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // copy
                writerBytes.writeBytesCopy(token, value, offset, length);
            } else {
                // default
                writerBytes.writeBytesDefault(token, value, offset, length);
            }
        }
    }

    // TODO: Z, add writeDup(int id) for repeating the last value sent,
    // this can avoid string check for copy operation if its already known that
    // we are sending the same value.

    public void write(int token, ByteBuffer buffer) {

        assert (0 != (token & (2 << TokenBuilder.SHIFT_TYPE)));
        assert (0 != (token & (4 << TokenBuilder.SHIFT_TYPE)));
        assert (0 != (token & (8 << TokenBuilder.SHIFT_TYPE)));

        if (0 == (token & (1 << TokenBuilder.SHIFT_TYPE))) {// compiler does all
                                                            // the work.
            acceptByteBuffer(token, buffer);
        } else {
            acceptByteBufferOptional(token, buffer);
        }
    }

    private void acceptByteBufferOptional(int token, ByteBuffer value) {
        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {// compiler does all
                                                            // the work.
            // none constant delta tail
            if (0 == (token & (6 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // none tail
                if (0 == (token & (8 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    writerBytes.writeBytesOptional(value);
                } else {
                    // tail
                    writerBytes.writeBytesTailOptional(token, value);
                }
            } else {
                // constant delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // constant
                    writerBytes.writeBytesConstantOptional(token);
                } else {
                    // delta
                    writerBytes.writeBytesDeltaOptional(token, value);
                }
            }
        } else {
            // copy default
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // copy
                writerBytes.writeBytesCopyOptional(token, value);
            } else {
                // default
                writerBytes.writeBytesDefaultOptional(token, value);
            }
        }
    }

    private void acceptByteBuffer(int token, ByteBuffer value) {
        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {// compiler does all
                                                            // the work.
            // none constant delta tail
            if (0 == (token & (6 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // none tail
                if (0 == (token & (8 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    writerBytes.writeBytes(value);
                } else {
                    // tail
                    writerBytes.writeBytesTail(token, value);
                }
            } else {
                // constant delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // constant
                    writerBytes.writeBytesConstant(token);
                } else {
                    // delta
                    writerBytes.writeBytesDelta(token, value);
                }
            }
        } else {
            // copy default
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // copy
                writerBytes.writeBytesCopy(token, value);
            } else {
                // default
                writerBytes.writeBytesDefault(token, value);
            }
        }
    }

    public void write(int token, CharSequence value) {

        assert (0 == (token & (4 << TokenBuilder.SHIFT_TYPE)));
        assert (0 != (token & (8 << TokenBuilder.SHIFT_TYPE)));

        if (0 == (token & (1 << TokenBuilder.SHIFT_TYPE))) {// compiler does all
                                                            // the work.
            if (0 == (token & (2 << TokenBuilder.SHIFT_TYPE))) {
                // ascii
                acceptCharSequenceASCII(token, value);
            } else {
                // utf8
                acceptCharSequenceUTF8(token, value);
            }
        } else {
            if (0 == (token & (2 << TokenBuilder.SHIFT_TYPE))) {
                // ascii optional
                acceptCharSequenceASCIIOptional(token, value);
            } else {
                // utf8 optional
                acceptCharSequenceUTF8Optional(token, value);
            }
        }
    }

    private void acceptCharSequenceUTF8Optional(int token, CharSequence value) {

        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {// compiler does all
                                                            // the work.
            // none constant delta tail
            if (0 == (token & (6 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // none tail
                if (0 == (token & (8 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    writerChar.writeUTF8Optional(value);
                } else {
                    // tail
                    writerChar.writeUTF8TailOptional(token, value);
                }
            } else {
                // constant delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // constant
                    writerChar.writeUTF8ConstantOptional(token);
                } else {
                    // delta
                    writerChar.writeUTF8DeltaOptional(token, value);
                }
            }
        } else {
            // copy default
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // copy
                writerChar.writeUTF8CopyOptional(token, value);
            } else {
                // default
                writerChar.writeUTF8DefaultOptional(token, value);
            }
        }
    }

    private void acceptCharSequenceUTF8(int token, CharSequence value) {

        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {// compiler does all
                                                            // the work.
            // none constant delta tail
            if (0 == (token & (6 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // none tail
                if (0 == (token & (8 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    writerChar.writeUTF8(value);
                } else {
                    // tail
                    writerChar.writeUTF8Tail(token, value);
                }
            } else {
                // constant delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // constant
                    writerChar.writeUTF8Constant(token);
                } else {
                    // delta
                    writerChar.writeUTF8Delta(token, value);
                }
            }
        } else {
            // copy default
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // copy
                writerChar.writeUTF8Copy(token, value);
            } else {
                // default
                writerChar.writeUTF8Default(token, value);
            }
        }

    }

    private void acceptCharSequenceASCIIOptional(int token, CharSequence value) {

        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {// compiler does all
                                                            // the work.
            // none constant delta tail
            if (0 == (token & (6 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // none tail
                if (0 == (token & (8 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    assert (TokenBuilder.isOpperator(token, OperatorMask.Field_None)) : "Found "
                            + TokenBuilder.tokenToString(token);
                    writerChar.writeASCIITextOptional(value);
                } else {
                    // tail
                    assert (TokenBuilder.isOpperator(token, OperatorMask.Field_Tail)) : "Found "
                            + TokenBuilder.tokenToString(token);
                    writerChar.writeASCIITailOptional(token, value);
                }
            } else {
                // constant delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // constant
                    assert (TokenBuilder.isOpperator(token, OperatorMask.Field_Constant)) : "Found "
                            + TokenBuilder.tokenToString(token);
                    writerChar.writeASCIIConstantOptional(token);
                } else {
                    // delta
                    assert (TokenBuilder.isOpperator(token, OperatorMask.Field_Delta)) : "Found "
                            + TokenBuilder.tokenToString(token);
                    writerChar.writeASCIIDeltaOptional(token, value);

                }
            }
        } else {
            // copy default
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // copy
                assert (TokenBuilder.isOpperator(token, OperatorMask.Field_Copy)) : "Found "
                        + TokenBuilder.tokenToString(token);
                writerChar.writeASCIICopyOptional(token, value);

            } else {
                // default
                assert (TokenBuilder.isOpperator(token, OperatorMask.Field_Default)) : "Found "
                        + TokenBuilder.tokenToString(token);
                writerChar.writeASCIIDefaultOptional(token, value);

            }
        }

    }

    private void acceptCharSequenceASCII(int token, CharSequence value) {

        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {// compiler does all
                                                            // the work.
            // none constant delta tail
            if (0 == (token & (6 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // none tail
                if (0 == (token & (8 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    writerChar.writeASCII(value);
                } else {
                    // tail
                    writerChar.writeASCIITail(token, value);
                }
            } else {
                // constant delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // constant
                    writerChar.writeASCIIConstant(token);
                } else {
                    // delta
                    writerChar.writeASCIIDelta(token, value);
                }
            }
        } else {
            // copy default
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // copy
                writerChar.writeASCIICopy(token, value);
            } else {
                // default
                writerChar.writeASCIIDefault(token, value);
            }
        }

    }

    public void write(int token, char[] value, int offset, int length) {

        assert (0 == (token & (4 << TokenBuilder.SHIFT_TYPE)));
        assert (0 != (token & (8 << TokenBuilder.SHIFT_TYPE)));

        if (0 == (token & (1 << TokenBuilder.SHIFT_TYPE))) {// compiler does all
                                                            // the work.
            if (0 == (token & (2 << TokenBuilder.SHIFT_TYPE))) {
                // ascii
                acceptCharArrayASCII(token, value, offset, length);
            } else {
                // utf8
                acceptCharArrayUTF8(token, value, offset, length);
            }
        } else {
            if (0 == (token & (2 << TokenBuilder.SHIFT_TYPE))) {
                // ascii optional
                acceptCharArrayASCIIOptional(token, value, offset, length);
            } else {
                // utf8 optional
                acceptCharArrayUTF8Optional(token, value, offset, length);
            }
        }
    }

    private void acceptCharArrayUTF8Optional(int token, char[] value, int offset, int length) {

        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {// compiler does all
                                                            // the work.
            // none constant delta tail
            if (0 == (token & (6 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // none tail
                if (0 == (token & (8 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    writerChar.writeUTF8Optional(value, offset, length);

                } else {
                    // tail
                    writerChar.writeUTF8TailOptional(token, value, offset, length);
                }
            } else {
                // constant delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // constant
                    writerChar.writeUTF8ConstantOptional(token);
                } else {
                    // delta
                    writerChar.writeUTF8DeltaOptional(token, value, offset, length);
                }
            }
        } else {
            // copy default
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // copy
                writerChar.writeUTF8CopyOptional(token, value, offset, length);
            } else {
                // default
                writerChar.writeUTF8DefaultOptional(token, value, offset, length);
            }
        }

    }

    private void acceptCharArrayUTF8(int token, char[] value, int offset, int length) {
        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {// compiler does all
                                                            // the work.
            // none constant delta tail
            if (0 == (token & (6 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // none tail
                if (0 == (token & (8 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    writerChar.writeUTF8(value, offset, length);

                } else {
                    // tail
                    writerChar.writeUTF8Tail(token, value, offset, length);
                }
            } else {
                // constant delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // constant
                    writerChar.writeUTF8Constant(token);
                } else {
                    // delta
                    writerChar.writeUTF8Delta(token, value, offset, length);
                }
            }
        } else {
            // copy default
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // copy
                writerChar.writeUTF8Copy(token, value, offset, length);
            } else {
                // default
                writerChar.writeUTF8Default(token, value, offset, length);
            }
        }

    }

    private void acceptCharArrayASCIIOptional(int token, char[] value, int offset, int length) {
        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {// compiler does all
                                                            // the work.
            // none constant delta tail
            if (0 == (token & (6 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // none tail
                if (0 == (token & (8 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    writerChar.writeASCIITextOptional(value, offset, length);
                } else {
                    // tail
                    writerChar.writeASCIITailOptional(token, value, offset, length);
                }
            } else {
                // constant delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // constant
                    writerChar.writeASCIIConstantOptional(token);
                } else {
                    // delta
                    writerChar.writeASCIIDeltaOptional(token, value, offset, length);
                }
            }
        } else {
            // copy default
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // copy
                writerChar.writeASCIICopyOptional(token, value, offset, length);
            } else {
                // default
                writerChar.writeASCIIDefaultOptional(token, value, offset, length);
            }
        }

    }

    private void acceptCharArrayASCII(int token, char[] value, int offset, int length) {

        if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {// compiler does all
                                                            // the work.
            // none constant delta tail
            if (0 == (token & (6 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // none tail
                if (0 == (token & (8 << TokenBuilder.SHIFT_OPER))) {
                    // none
                    writerChar.writeASCIIText(token, value, offset, length);
                } else {
                    // tail
                    writerChar.writeASCIITail(token, value, offset, length);
                }
            } else {
                // constant delta
                if (0 == (token & (4 << TokenBuilder.SHIFT_OPER))) {
                    // constant
                    writerChar.writeASCIIConstant(token);
                } else {
                    // delta
                    writerChar.writeASCIIDelta(token, value, offset, length);
                }
            }
        } else {
            // copy default
            if (0 == (token & (2 << TokenBuilder.SHIFT_OPER))) {// compiler does
                                                                // all the work.
                // copy
                writerChar.writeASCIICopy(token, value, offset, length);
            } else {
                // default
                writerChar.writeASCIIDefault(token, value, offset, length);
            }
        }
    }

    public void openGroup(int token, int pmapSize) {
        assert (token < 0);
        assert (0 == (token & (OperatorMask.Group_Bit_Close << TokenBuilder.SHIFT_OPER)));
        assert (0 == (token & (OperatorMask.Group_Bit_Templ << TokenBuilder.SHIFT_OPER)));

        if (0 != (token & (OperatorMask.Group_Bit_PMap << TokenBuilder.SHIFT_OPER))) {
            writer.openPMap(pmapSize);
        }

    }

    public void openGroup(int token, int templateId, int pmapSize) {
        assert (token < 0);
        assert (0 == (token & (OperatorMask.Group_Bit_Close << TokenBuilder.SHIFT_OPER)));
        assert (0 != (token & (OperatorMask.Group_Bit_Templ << TokenBuilder.SHIFT_OPER)));

        if (pmapSize > 0) {
            writer.openPMap(pmapSize);
        }
        // done here for safety to ensure it is always done at group open.
        pushTemplate(templateId);
    }

    // must happen just before Group so the Group in question must always have
    // an outer group.
    private void pushTemplate(int templateId) {
        int top = templateStack[templateStackHead];
        if (top == templateId) {
            writer.writePMapBit((byte) 0);
        } else {
            writer.writePMapBit((byte) 1);
            writer.writeIntegerUnsigned(templateId);
            top = templateId;
        }

        templateStack[templateStackHead++] = top;
    }

    public void closeGroup(int token) {
        assert (token < 0);
        assert (0 != (token & (OperatorMask.Group_Bit_Close << TokenBuilder.SHIFT_OPER)));

        if (0 != (token & (OperatorMask.Group_Bit_PMap << TokenBuilder.SHIFT_OPER))) {
            writer.closePMap();
        }

        if (0 != (token & (OperatorMask.Group_Bit_Templ << TokenBuilder.SHIFT_OPER))) {
            // must always pop because open will always push
            templateStackHead--;
        }

    }

    public void flush() {
        writer.flush();
    }

    public void reset() {

        dictionaryFactory.reset(writerInteger.dictionary);
        dictionaryFactory.reset(writerLong.dictionary);
        writerDecimal.reset(dictionaryFactory);
        writerChar.reset(dictionaryFactory);
        writerBytes.reset(dictionaryFactory);
        templateStackHead = 0;
        sequenceCountStackHead = 0;
    }

    public boolean isFirstSequenceItem() {
        return isFirstSequenceItem;
    }

    public boolean isSkippedSequence() {
        return isSkippedSequence;
    }

    // long fieldCount = 0;

    public boolean dispatchWriteByToken(int fieldPos) {

        int token = fullScript[activeScriptCursor];

        assert (gatherWriteData(writer, token, activeScriptCursor, fieldPos, queue));

        if (0 == (token & (16 << TokenBuilder.SHIFT_TYPE))) {
            // 0????
            if (0 == (token & (8 << TokenBuilder.SHIFT_TYPE))) {
                // 00???
                if (0 == (token & (4 << TokenBuilder.SHIFT_TYPE))) {
                    writeInteger(token, queue.readInteger(fieldPos));
                } else {
                    writeLong(token, queue.readLong(fieldPos));
                }
            } else {
                // 01???
                if (0 == (token & (4 << TokenBuilder.SHIFT_TYPE))) {
                    char[] buffer = queue.readRingCharBuffer(fieldPos);
                    int length = queue.readCharsLength(fieldPos);
                    if (length < 0) {
                        write(token);
                    } else {
                        write(token,
                                charSequence(buffer, queue.readRingCharPos(fieldPos), queue.readRingCharMask(), length));
                    }
                } else {
                    // 011??
                    if (0 == (token & (2 << TokenBuilder.SHIFT_TYPE))) {
                        // 0110? Decimal and DecimalOptional
                        

                        
                        int exponent = queue.readInteger(fieldPos);
                        long mantissa = queue.readLong(fieldPos + 1);//TODO: A, writer must break these into two
                        
                        
                        if (0 == (token & (1 << TokenBuilder.SHIFT_TYPE))) {
                            writerDecimal.writeExponent(token, exponent);
                            
                            //NOTE: moving forward one to get second token for decimals
                            token = fullScript[++activeScriptCursor];
                            
                            writerDecimal.writeMantissa(token, mantissa);
                        } else {
                            if (TemplateCatalog.DEFAULT_CLIENT_SIDE_ABSENT_VALUE_INT==exponent) {
                            	writerDecimal.writerDecimalExponent.writeNull(token);
                            } else {
                            	writerDecimal.writeExponentOptional(token, exponent);
                            }
                            
                            //NOTE: moving forward one to get second token for decimals
                            token = fullScript[++activeScriptCursor];
                            
                            if (TemplateCatalog.DEFAULT_CLIENT_SIDE_ABSENT_VALUE_LONG==mantissa) {
                            	writerDecimal.writerDecimalMantissa.writeNull(token);
                            } else {
                            	writerDecimal.writeMantissaOptional(token, mantissa);
                            }
                        }
                    } else {
                        // //0111? ByteArray
                        if (0 == (token & (1 << TokenBuilder.SHIFT_TYPE))) {
                            // 01110 ByteArray
                            // queue.selectByteSequence(fieldPos);
                            // write(token,queue); TODO: B, copy the text
                            // implementation
                        } else {
                            // 01111 ByteArrayOptional
                            // queue.selectByteSequence(fieldPos);
                            // write(token,queue); TODO: B, copy the text
                            // implementation
                        }
                    }
                }
            }
        } else {
            if (0 == (token & (8 << TokenBuilder.SHIFT_TYPE))) {
                // 10???
                if (0 == (token & (4 << TokenBuilder.SHIFT_TYPE))) {
                    // 100??
                    // Group Type, no others defined so no need to keep checking
                    if (0 == (token & (OperatorMask.Group_Bit_Close << TokenBuilder.SHIFT_OPER))) {

                        isSkippedSequence = false;
                        isFirstSequenceItem = false;
                        // this is NOT a message/template so the non-template
                        // pmapSize is used.
                        // System.err.println("open group:"+TokenBuilder.tokenToString(token));
                        openGroup(token, nonTemplatePMapSize);

                    } else {
                        // System.err.println("close group:"+TokenBuilder.tokenToString(token));
                        closeGroup(token);// closing this seq causing throw!!
                        if (0 != (token & (OperatorMask.Group_Bit_Seq << TokenBuilder.SHIFT_OPER))) {
                            // must always pop because open will always push
                            if (0 == --sequenceCountStack[sequenceCountStackHead]) {
                                sequenceCountStackHead--;// pop sequence off
                                                         // because they have
                                                         // all been used.
                                return false;// this sequence is done.
                            } else {
                                return true;// true if this sequence must be
                                            // visited again.
                            }
                        }
                    }

                } else {
                    // 101??
                    // Length Type, no others defined so no need to keep
                    // checking
                    // Only happens once before a node sequence so push it on
                    // the count stack
                    int length = queue.readInteger(fieldPos);
                    writeInteger(token, length);

                    if (length == 0) {
                        isFirstSequenceItem = false;
                        isSkippedSequence = true;
                    } else {
                        isFirstSequenceItem = true;
                        isSkippedSequence = false;
                        sequenceCountStack[++sequenceCountStackHead] = length;
                    }
                    return true;
                }
            } else {
                // 11???
                // Dictionary Type, no others defined so no need to keep
                // checking
                if (0 == (token & (1 << TokenBuilder.SHIFT_OPER))) {
                    // reset the values
                    int dictionary = TokenBuilder.MAX_INSTANCE & token;

                    int[] members = dictionaryMembers[dictionary];
                    // System.err.println(members.length+" "+Arrays.toString(members));

                    int m = 0;
                    int limit = members.length;
                    if (limit > 0) {
                        int idx = members[m++];
                        while (m < limit) {
                            assert (idx < 0);

                            if (0 == (idx & 8)) {
                                if (0 == (idx & 4)) {
                                    // integer
                                    while (m < limit && (idx = members[m++]) >= 0) {
                                        writerInteger.dictionary[idx] = writerInteger.init[idx];
                                    }
                                } else {
                                    // long
                                    while (m < limit && (idx = members[m++]) >= 0) {
                                        writerLong.dictionary[idx] = writerLong.init[idx];
                                    }
                                }
                            } else {
                                if (0 == (idx & 4)) {
                                    // text
                                    while (m < limit && (idx = members[m++]) >= 0) {
                                        writerChar.reset(idx);
                                    }
                                } else {
                                    if (0 == (idx & 2)) {
                                        // decimal
                                        while (m < limit && (idx = members[m++]) >= 0) {
                                            writerDecimal.reset(idx);
                                        }
                                    } else {
                                        // bytes
                                        while (m < limit && (idx = members[m++]) >= 0) {
                                            writerBytes.reset(idx);
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // use last value from this location
                    readFromIdx = TokenBuilder.MAX_INSTANCE & token;
                }

            }

        }
        return false;
    }

    private CharSequence charSequence(char[] buffer, int pos, int mask, int length) {
        return ringCharSequence.set(buffer, pos, mask, length);
    }

    private boolean gatherWriteData(PrimitiveWriter writer, int token, int cursor, int fieldPos, FASTRingBuffer queue) {

        if (null != observer) {

            String value = "";
            int type = TokenBuilder.extractType(token);
            if (type == TypeMask.GroupLength || type == TypeMask.IntegerSigned
                    || type == TypeMask.IntegerSignedOptional || type == TypeMask.IntegerUnsigned
                    || type == TypeMask.IntegerUnsignedOptional) {

                value = "<" + queue.readInteger(fieldPos) + ">";

            } else if (type == TypeMask.Decimal || type == TypeMask.DecimalOptional) {

                value = "<e:" + queue.readInteger(fieldPos) + "m:" + queue.readLong(fieldPos + 1) + ">";

            } else if (type == TypeMask.TextASCII || type == TypeMask.TextASCIIOptional || type == TypeMask.TextUTF8
                    || type == TypeMask.TextUTF8Optional) {
                value = "<len:" + queue.readCharsLength(fieldPos) + ">";
            }

            // TotalWritten is updated each time the pump pulls more bytes to
            // write.

            long absPos = writer.totalWritten() + writer.bytesReadyToWrite();
            // TODO: Z, this position is never right because it is changed by
            // the pmap length which gets trimmed.

            observer.tokenItem(absPos, token, cursor, value);
        }

        return true;
    }

    public void dispatchPreable(byte[] preambleData) {
        writer.writeByteArrayData(preambleData, 0, preambleData.length);
    }

    public void openMessage(int pmapMaxSize, int templateId) {

        writer.openPMap(pmapMaxSize);
        writer.writePMapBit((byte) 1);
        writer.closePMap();// TODO: A, this needs to be close but not sure this
                           // is the right location.
        writer.writeIntegerUnsigned(templateId);

    }

}