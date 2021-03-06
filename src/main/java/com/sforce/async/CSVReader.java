package com.sforce.async;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StreamTokenizer;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

/*
 * Copyright, 1999, SALESFORCE.com
 */

/**
 * Parse a CSV file into lines of fields.
 */

public class CSVReader {

    // During import we replace this char with a space
    //private static final char PARSE_FIND = '\u00A0';

    private StreamTokenizer parser;
    private char separator;
    private boolean ignoreBlankRecords = true;
    private int maxSizeOfIndividualCell = 32000;
    private int maxColumnsPerRow = 5000;
    private int maxRowSizeInCharacters = 400000; //400K of characters in a row..

    //by default, giving a 10m character limit. Note that this limit is in characters. if you want to limit
    // by bytes, do it separately.
    //Call the mutator to set the limit for following.
    private int maxFileSizeInCharacters = 10000000;
    private int maxRowsInFile = 10001;

    private int fileSizeInCharacters = 0;
    private int rowsInFile = 0;
    private int maxFieldCount;

    boolean atEOF;

    public CSVReader(Reader input) {
        this(new BufferedReader(input));
    }

    public CSVReader(Reader input, char customizedSeparator) {
        this(new BufferedReader(input), customizedSeparator);
    }

    public CSVReader(InputStream input) {
        this(new InputStreamReader(input));
    }

    public CSVReader(InputStream input, String enc) throws UnsupportedEncodingException {
        this(new InputStreamReader(input, enc));
    }

    public CSVReader(InputStream input, String enc, char customizedSeparator) throws UnsupportedEncodingException {
        this(new InputStreamReader(input, enc), customizedSeparator);
    }

    public CSVReader(BufferedReader input) {
        this(input, ',');
    }

    public CSVReader(BufferedReader input, char customizedSeparator) {
        this.separator = customizedSeparator;
        
        parser = new StreamTokenizer(input);
        parser.ordinaryChars(0, 255);
        parser.wordChars(0, 255);
        parser.ordinaryChar('\"');
        parser.ordinaryChar(customizedSeparator);

        // Need to do set EOL significance after setting ordinary and word
        // chars, and need to explicitly set \n and \r as whitespace chars
        // for EOL detection to work
        parser.eolIsSignificant(true);
        parser.whitespaceChars('\n', '\n');
        parser.whitespaceChars('\r', '\r');
        atEOF = false;
    }

    private void checkRecordExceptions(List<String> line) throws IOException {
        int rowSizeInCharacters = 0;
        if (line != null) {
            for (String value : line) {
                if (value != null) {
                    rowSizeInCharacters += value.length();
                }
            }

            if (rowSizeInCharacters > maxRowSizeInCharacters) {
                throw new CSVParseException("Exceeded max length for one record: " + rowSizeInCharacters +
                                            ". Max length for one record should be less than or equal to " +
                                            maxRowSizeInCharacters, parser.lineno());
            }

            fileSizeInCharacters += rowSizeInCharacters;

            if (fileSizeInCharacters > maxFileSizeInCharacters) {
                throw new CSVParseException("Exceeded max file size: " + fileSizeInCharacters +
                                            ". Max file size in characters should be less than or equal to " +
                                            maxFileSizeInCharacters, parser.lineno());
            }

            rowsInFile++;

            if (rowsInFile > maxRowsInFile) {
                throw new CSVParseException("Exceeded number of records : " + rowsInFile +
                                            ". Number of records should be less than or equal to " + maxRowsInFile,
                                            parser.lineno());
            }
        }
    }


    public ArrayList<String> nextRecord() throws IOException {
        ArrayList<String> record = nextRecordLocal();

        if (ignoreBlankRecords) {
            while(record != null) {
                boolean emptyLine = false;

                if (record.size() == 0) {
                    emptyLine = true;
                } else if (record.size() == 1) {
                    String val = record.get(0);
                    if (val == null || val.length() == 0) {
                        emptyLine = true;
                    }
                }

                if (emptyLine) {
                    record = nextRecordLocal();
                } else {
                    break;
                }
            }
        }

        checkRecordExceptions(record);
        return record;
    }

    private ArrayList<String> nextRecordLocal() throws IOException {
        if (atEOF) {
            return null;
        }

        ArrayList<String> record = new ArrayList<String>(maxFieldCount);

        StringBuilder fieldValue = null;

        while(true) {
            int token = parser.nextToken();

            if (token == StreamTokenizer.TT_EOF) {
                addField(record, fieldValue);
                atEOF = true;
                break;
            }

            if (token == StreamTokenizer.TT_EOL) {
                addField(record, fieldValue);
                break;
            }

            if (token == separator) {
                addField(record, fieldValue);
                fieldValue = null;
                continue;
            }

            if (token == StreamTokenizer.TT_WORD) {
                if (fieldValue != null) {
                    throw new CSVParseException("Unknown error", parser.lineno());
                }

                fieldValue = new StringBuilder(parser.sval);
                continue;
            }

            if (token == '"') {
                if (fieldValue != null) {
                    throw new CSVParseException("Found unescaped quote. A value with quote should be within a quote",
                            parser.lineno());
                }

                while(true) {
                    token = parser.nextToken();

                    if (token == StreamTokenizer.TT_EOF) {
                        atEOF = true;
                        throw new CSVParseException("EOF reached before closing an opened quote", parser.lineno());
                    }

                    if (token == separator) {
                        fieldValue = appendFieldValue(fieldValue, token);
                        continue;
                    }

                    if (token == StreamTokenizer.TT_EOL) {
                        fieldValue = appendFieldValue(fieldValue, "\n");
                        continue;
                    }


                    if (token == StreamTokenizer.TT_WORD) {
                        fieldValue = appendFieldValue(fieldValue, parser.sval);
                        continue;
                    }


                    if (token == '"') {
                        int nextToken = parser.nextToken();

                        if (nextToken == '"') {
                            //escaped quote
                            fieldValue = appendFieldValue(fieldValue, nextToken);
                            continue;
                        }

                        if (nextToken == StreamTokenizer.TT_WORD) {
                            throw new CSVParseException("Not expecting more text after end quote", parser.lineno());
                        } else {
                            parser.pushBack();
                            break;
                        }
                    }
                }
            }
        }

        if (record.size() > maxFieldCount) {
            maxFieldCount = record.size();
        }

        return record;
    }

    private StringBuilder appendFieldValue(StringBuilder fieldValue, int token) throws CSVParseException {
        return appendFieldValue(fieldValue, ""+(char)token);
    }

    private StringBuilder appendFieldValue(StringBuilder fieldValue, String token) throws CSVParseException {
        if (fieldValue == null) {
            fieldValue = new StringBuilder();
        }

        fieldValue.append(token);

        if (token.length() > maxSizeOfIndividualCell) {
            throw new CSVParseException("Exceeded max field size: " + token.length(), parser.lineno());
        }

        return fieldValue;
    }


    private void addField(ArrayList<String> record, StringBuilder fieldValue) throws CSVParseException {
        record.add(fieldValue == null ? null : fieldValue.toString());

        if (record.size() > maxColumnsPerRow) {
            throw new CSVParseException("Exceeded max number of columns per record : " + maxColumnsPerRow,
                    parser.lineno());
        }
    }

    public int getMaxRowsInFile() {
        return this.maxRowsInFile;
    }

    public void setMaxRowsInFile(int newMax) {
        this.maxRowsInFile = newMax;
    }
    //*****************
    // Excption classes
    //*****************


    public static class CSVParseException extends IOException {
        final int recordNumber;

        CSVParseException(String message, int lineno) {
            super(message);
            recordNumber = lineno;
        }

        CSVParseException(int i) {
            recordNumber = i;
        }

        public int getRecordNumber() {
            return recordNumber;
        }
    }
}
