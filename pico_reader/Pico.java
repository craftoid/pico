
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Calendar;
import java.util.StringTokenizer;

import processing.core.PApplet;
import processing.data.Table;
import processing.data.TableRow;

/*
   * Analyses a pico PLW file to retrieve data.
 * Header is structured as follows:
 * 
 * Byte Usage          Offset of data start from start of file
 * uint16 header size      0
 * char[40] signature       2
 * uint32 version         42
 * uint32 no. of parameters   46
 * uint16[250] parameters     50
 * uint32 sample no.       550
 * uint32 sample no. (twice)   554
 * uint32 max sample       558
 * uint32 interval         562
 * uint16 interval units     566
 * uint32 trigger sample     568
 * uint16 triggered       572
 * uint32 first sample       574
 * uint32 sample byte       578
 * uint32 setting byte       582
 * uint32 start date       586
 * uint32 start time       590
 * uint32 min time         594
 * uint32 max time         598
 * char[1000] notes       602
 * uint32 current time       1602
 * uint8[78] spare        1606
 * data start point        1684
 * 
 * 
 * Data follows as:
 * 
 * uint32 sample number
 * float32 sample (* n channels)
 * 
 */

/**
 * The PICO logging system can sometimes crash, leaving
 * the user with an incomplete log file that cannot be read back with
 * the PICO system. This set of classes allows the raw data to be read
 * back out of the log file and converted into CSV format.
 * The header information in the PLW file is checked to find the number of channels
 * logged. There is also a location containing the last sample number recorded. This
 * is in two locations. This program checks these two values and takes the lowest of the
 * two to use as the end of data marker.
 * Large log files can be converted into CSV format, but if these are to be analysed
 * eventually in excel (graphs, charts), excel has a maximum of 65535 lines it can
 * handle. Any more lines are simply discarded.
 * 
 * @author r_martin
 *
 */


public class Pico {
  /**
   * Stores the tagged version of this file in CVS. This is added
   * into the frame title.
   */

  private static final String VERSION = "V5.3.7";

  private static final long serialVersionUID = -831223901639431885L;

  /**
   * Enums for end of conversion or mid-conversion errors.
   */
  private static final int FAIL = 0, PASS = 1, READ_ERROR = -999867772;

  /**
   * Used for reading in data from the PLW file header. At a given position
   * how many bytes do we want to read in to convert.
   */
  private static final int GET_4_BYTES = 4, GET_2_BYTES = 2, MOVE_ONLY = 0;

  /**
   * Enums for sampling time
   */
  private static final String [] intervalTypes = {
    "fs", "ps", "ns", "us", "ms", "s", "min", "hour"
  };
  private static final double [] intervalMultipliers = {
    Math.pow(10.0, -15.0), Math.pow(10.0, -12.0), Math.pow(10.0, -9.0), Math.pow(10.0, -6.0), Math.pow(10.0, -3.0), 1, 60, 3600
  };

  /**
   * The Java Calendar object uses milliseconds. This number represents
   * the number of milliseconds in a day.
   */
  private static final long msPerDay = 86400000;

  /**
   * An empirically arrived-at number to convert between the C-style date
   * stored in the PLW file and a Java Calendar object. This represents the
   * difference in days.
   */
  private static final int cToJavaDelta = 719163;

  private static final boolean NO_CHANGE_TEXT = false, CHANGE_TEXT = true;

  private float minimumSample = Float.MAX_VALUE;
  private float maximumSample = Float.MIN_VALUE;

  private boolean fileIncrement;

  private int numChannels, MAX_LINES, filePosition = 0, headerSize, interval;
  private int lastSample, currentSample = 0, timingUnits;
  private Calendar startDate;
  private FileInputStream inFile = null;
  private int headerVersion;
  private int startTimeInt;
  private String fileNameIn;

  private PApplet app;


  public Pico(PApplet app) {
    this.app = app;
  }

  public Table load(String filename) {

    Table table = new Table();

    fileNameIn = filename;

    int timeMarker, fNum, numLines = 0, startDateInt = 0;
    byte [] inByte;
    float [] samples;

    try {

      inFile = new FileInputStream(new File(app.dataPath(fileNameIn)));
      StringTokenizer st = new StringTokenizer(fileNameIn, ".");
      String fileNameStart = st.nextToken();

      fNum = 1;

      // Get actual header size
      headerSize = readFilePosition(0, GET_2_BYTES);          
      if (headerSize == READ_ERROR) {
        System.err.println("Error reading Header Size.");
        return null;
      }

      // Get header version. This program copes with versions 3 and 4 only at the moment.
      headerVersion = readFilePosition(42, GET_4_BYTES);
      if (headerVersion == READ_ERROR) {
        System.err.println("Error reading Header Version.");
        return null;
      }
      if (headerVersion < 3) {
        System.err.println("Header Version: "+headerVersion+" not supported.");
        closeAll(FAIL);
        return null;
      }

      // Get number of channels
      numChannels = readFilePosition(46, GET_4_BYTES);
      if (numChannels == READ_ERROR) {
        System.err.println("Error reading Number of Channels.");
        return null;
      }

      /* Find last sample taken. 
       * There are 2 in the file - get both and take the lowest.
       */
      lastSample = Math.min(readFilePosition(550, GET_4_BYTES), readFilePosition(554, GET_4_BYTES));

      // Get interval
      interval = readFilePosition(562, GET_4_BYTES);
      if (interval == READ_ERROR) {
        System.err.println("Error reading Interval.");
        return null;
      }

      // Get timing units
      timingUnits = readFilePosition(566, GET_4_BYTES);
      if (timingUnits == READ_ERROR) {
        System.err.println("Error reading Timing Units.");
        return null;
      }

      // Get start date in C format
      startDateInt = readFilePosition(586, GET_4_BYTES);
      if (startDateInt == READ_ERROR) {
        System.err.println("Error reading Start Date.");
        return null;
      }

      // Get start time
      startTimeInt = readFilePosition(590, GET_4_BYTES);
      if (startTimeInt == READ_ERROR) {
        System.err.println("Error reading Start Time.");
        return null;
      }

      double hour, minute, second;
      int hourInt, minuteInt, secondInt;
      hour = (double)startTimeInt/3600d;
      hourInt = (int)hour;
      minute = (hour-(double)hourInt)*60;
      minuteInt = (int)minute;
      second = (minute-(double)minuteInt)*60;
      secondInt = (int)second;

      // Set the date and time. Convert from C to Java.
      startDate = Calendar.getInstance();
      startDate.clear(); // TODO check this is needed
      startDate.setTimeInMillis((startDateInt-cToJavaDelta)*msPerDay);
      startDate.set(Calendar.HOUR_OF_DAY, hourInt);
      startDate.set(Calendar.MINUTE, minuteInt);
      startDate.set(Calendar.SECOND, secondInt);

      // Move to start of data
      readFilePosition(headerSize, MOVE_ONLY);

      // Print header of output file
      outputFileHeader();

      // Create table columns
      table.addColumn("time");
      for (int i = 0; i < numChannels; i++) {
        table.addColumn("channel " + (i + 1));
      }


      // Now at start of data.
      inByte = new byte[4*(numChannels+1)]; // time marker (4 bytes) + 4 bytes per channel sample.
      samples = new float[numChannels];

      while ( ( (inFile.read (inByte) ==  (4*(numChannels+1))) & currentSample<lastSample)) {

        timeMarker = arr2int(inByte, 0);
        for (int i = 0; i<numChannels; i++) {
          float sample = arr2float(inByte, 4*(i+1));

          // add sample to the array
          samples[i] = sample;

          // keep track of the bounding box
          minimumSample = app.min(sample, minimumSample);
          maximumSample = app.max(sample, maximumSample);
        }

        TableRow row = table.addRow();
        row.setInt("time", timeMarker);

        // PRINT DATE
        //System.out.println(timeMarker+","+dateConvert(calcSampleDate(timeMarker)));
        for (int i = 0; i<numChannels; i++) { 
          row.setFloat("channel " + (i + 1), samples[i]);
        }

        numLines++;
        currentSample++;
        if (numLines % 10 ==  0) {
          // System.err.println("File Number: "+fNum+"\n"+"Line: "+numLines);
        }
        if ((numLines == MAX_LINES) & fileIncrement) {

          fNum++;
          numLines = 0;
        }
      }
    } 
    catch (FileNotFoundException e) {
      System.err.println("Error - File not found");
      e.getMessage();
      closeAll(FAIL);
      return null;
    } 
    catch (IOException e) {
      System.err.println("Error - File IO");
      e.getMessage();
      closeAll(FAIL);
      return null;
    }    
    closeAll(PASS);

    return table;
  }


  public int getChannelCount() {
    return numChannels;
  }

  public void printInfo() {
    outputFileHeader();
  }

  public float getMaximumSample() {
    return maximumSample;
  }

  public float getMinimumSample() {
    return minimumSample;
  }

  public int getSampleCount() {
    return lastSample;
  }

  public int getSampleInterval() {
    return interval;
  }


  private Calendar calcSampleDate(int timeMarker) {
    Calendar sd  = (Calendar)startDate.clone();
    sd.add(Calendar.SECOND, (int)(timeMarker*intervalMultipliers[timingUnits]));
    return sd;
  }

  /**
   * Header information is directed to the output file.
   */

  private void outputFileHeader() {
    app.println("Pico Reader Version:", VERSION);
    app.println("PLW Version:", headerVersion);
    app.println("File Name:", fileNameIn);
    app.println("Date of Test:", dateConvert(startDate));
    app.println("Number of Channels:", numChannels);
    app.println("Last Sample Number:", lastSample);
    app.println("Sample Interval:", interval + intervalTypes[timingUnits]);
  }

  private String dateConvert(Calendar calIn) {
    int day, year, month, hour, minute, second;
    day = calIn.get(Calendar.DAY_OF_MONTH);
    month = calIn.get(Calendar.MONTH)+1; // Month starts at 0 = January
    year = calIn.get(Calendar.YEAR);
    hour = calIn.get(Calendar.HOUR_OF_DAY);
    minute = calIn.get(Calendar.MINUTE);
    second = calIn.get(Calendar.SECOND);
    return (day+"/"+month+"/"+year+" "+hour+":"+minute+":"+second);
  }

  /**
   * Method to get to a particular place in the input file.
   * This method can only move forwards in the file, never backwards.
   * 
   * @param positionToGetTo Byte position
   * @param numBytesRequired Number of bytes to read in and decode. 0 is a special
   * case which just moves the position in the file without decoding any data.
   * @return Decoded series of bytes.
   */
  private int readFilePosition(int positionToGetTo, int numBytesRequired) {
    int bytesToSkip = positionToGetTo-filePosition;
    if (bytesToSkip < 0) {
      System.err.println("Trying to skip backwards in input file");
      closeAll(FAIL);
      return READ_ERROR;
    }
    try {
      if (inFile.skip(bytesToSkip) !=  bytesToSkip) {
        System.err.println("Could not skip required number of bytes in input file");
        closeAll(FAIL);
        return READ_ERROR;
      }
      byte [] temp;
      switch (numBytesRequired) {
      case 2:
        temp = new byte[2];
        if (inFile.read(temp) !=  2) {
          System.err.println("Could not skip required number of bytes in input file");
          closeAll(FAIL);
          return READ_ERROR;
        }
        filePosition += bytesToSkip+numBytesRequired;
        return arr2int2(temp);
      case 4:
        temp = new byte[4];
        if (inFile.read(temp) !=  4) {
          System.err.println("Could not skip required number of bytes in input file");
          closeAll(FAIL);
          return READ_ERROR;
        }
        filePosition += bytesToSkip+numBytesRequired;
        return arr2int(temp, 0);
      case 0:
        filePosition += bytesToSkip+numBytesRequired;
        return 0;
      default:
        return READ_ERROR;
      }
    } 
    catch (IOException e) {
      System.err.println("Could not skip required number of bytes in input file");
      closeAll(FAIL);
      return READ_ERROR;
    }
  }

  /**
   * Closes all files and waits for shutdown through closure of the frame.
   * 
   * @param pf pass/fail. Use class constants.
   */
  private void closeAll(int pf) {
    try {
      if (inFile !=  null) inFile.close();
    } 
    catch (IOException e) {
      System.err.print("Error - could not close input file.");
      e.printStackTrace();
    }
    System.err.println("PLW Import " + ((pf == FAIL) ? "Failed" : "Finished")+ ".");
  }

  /**
   * Converts a 2-byte number to an integer.
   * 
   * @param temp array containing bytes (low first).
   * @return integer value.
   */
  private int arr2int2(byte[] temp) {
    int [] inI;
    inI = new int[2];
    for (int r = 0; r<2; r++) {
      inI[r] = (temp[r]<0)?temp[r]+256:temp[r]; // Add 256 if negative
    }
    return inI[1]*256+inI[0];
  }

  /**
   * Converts a 4-byte float to an integer.
   * 
   * @param inB Array of bytes to convert.
   * @param i Start position in array.
   * @return integer value.
   */
  private static int arr2int(byte[] inB, int i) {
    int [] inI;
    inI = new int[inB.length];
    for (int r = 0; r<inB.length; r++) {
      inI[r] = (inB[r]<0)?inB[r]+256:inB[r]; // Add 256 if negative
    }
    return (inI[i]+256*inI[i+1]+65536*inI[i+2]+16777216*inI[i+3]);
  }

  /**
   * Converts a 4-byte float to a float.
   * 
   * @param arr Array of bytes to convert.
   * @param start Start position in array.
   * @return float value.
   */
  private static float arr2float (byte[] arr, int start) {
    int i = 0;
    int len = 4;
    int cnt = 0;
    int[] tmp = new int[len];
    for (i = start; i < (start + len); i++) {
      tmp[cnt] = arr[i];
      cnt++;
    }
    int accum = 0;
    i = 0;
    for ( int shiftBy = 0; shiftBy < 32; shiftBy +=  8 ) {
      accum |=  ( (long)( tmp[i] & 0xff ) ) << shiftBy;
      i++;
    }
    return Float.intBitsToFloat(accum);
  }
}

