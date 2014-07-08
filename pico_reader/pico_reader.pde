
///////////////////////////////////////
//                                   //
//           Pico Reader             //
//                                   //
///////////////////////////////////////

// (c) Martin Schneider 2014

// Based on Pico PLW to CSV Converter code by Rich Martin
// http://sourceforge.net/projects/picoplwconvert/
// Relased under GPL v2

////////////// Key Map /////////////////

// [+] Zoom in 
// [-] Zoom out
// [1] ... [9] toggle channels
// [i] file info
// [p] print data to the console
// [s] save data in CSV format


// - Drag the mouse to scroll 
// - Use your mousewheel to zoom

Pico pico;
int channels;
Table table;
float ymin, ymax;
int TMIN, TMAX;
int tmin, tmax;
int showChannels = 0xff;

/// PARAMS ///

String input = "mydata.plw";
String output = "mydata.csv";

color[] palette = new color[]  {
   #aa6666, #66aa66, #66aaaa, #666666
};

float mouseWheelResolution = 20;
float zoomFactor = sqrt(2);
float zoom = 1;
int border = 10;


void setup() {

  size(800, 200);

  pico = new Pico(this);
  table = pico.load(input);
  channels = pico.getChannelCount();
  ymin = pico.getMaximumSample();
  ymax = pico.getMinimumSample();
  TMAX = pico.getSampleCount() * pico.getSampleInterval();
  tmax = int(TMAX / zoom);
  
  noLoop();
  
}

void draw() {

  background(255);
  noFill();
  
  for (int i = 0; i < channels; i++) {
    
    if(visibleChannel(i)) {
      
      // pick a color
      stroke(palette[i % palette.length]);
  
      // plot the curve
      beginShape();
      for (TableRow row : table.rows ()) {
        
        // get coords
        int time = row.getInt("time");
        float sample = row.getFloat(i + 1); 
        float y = map(sample, ymin, ymax, height - border, border);
        float x = map(time, tmin, tmax, 0, width);
  
        // only show vertices in the visible range
        // if (x > 0 && x < width) 
        vertex(x, y);
      }
      endShape();
    }
  }
}


void mouseDragged() {
  float dragx = mouseX - pmouseX;
  tmin -= dragx * TMAX / width / zoom;
  tmax -= dragx * TMAX / width / zoom;
  redraw();
}

void mouseWheel(MouseEvent event) {
  float e = event.getCount();
  float factor = pow(zoomFactor, e / mouseWheelResolution);
  zoomBy(factor, mouseX);
  redraw();
}

void keyPressed() {
  int tmid;

  switch(key) {
  case 's': 
    saveTable(table, output);
    break;
  case 'i':
    pico.printInfo();
    println();
    break;
  case 'p':
    printTable(table);
    println();
    break;
  case '-':
    zoomBy(1.0 / zoomFactor, mouseX);
    break;
  case '+':
    zoomBy(zoomFactor, mouseX);
    break;
  }
  if(key >= '1' && key <= '9') {
    toggleChannel(key - '1'); 
  }
  redraw();
}


// check if the channel is visible
boolean visibleChannel(int id) {
  return(showChannels & (1<<id)) > 0;
}

// xor the visibility bit of the channel
void toggleChannel(int id) {
  showChannels ^= (1<<id);
  println(showChannels);
}


// dynamically change the zoom level, so that the focus of the zoom 
// stays at the mouse tip

void zoomBy(float factor, int centerX) {
  zoom *= factor;
  int tpick = (int) map(centerX, 0, width, tmin, tmax);
  int tmid = (tmax + tmin) / 2;
  int TMID = (TMAX + TMIN) / 2;
  tmin = int(tpick - TMID / zoom + (tmid - tpick) / factor );
  tmax = int(tpick + TMID / zoom + (tmid - tpick) / factor );
}


// simply print out all the data values
void printTable(Table table) {
  for (TableRow row : table.rows ()) {
    int time = row.getInt(0);
    float[] samples = new float[channels];
    for (int i = 0; i < channels; i++ ) {
      samples[i] = row.getFloat(i + 1);
    }
    println(time, ": ", join(str(samples), ", "));
  }
}


