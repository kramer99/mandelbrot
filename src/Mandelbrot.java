//	Copyright 2009 Craig Henderson
//	
//	This program is free software: you can redistribute it and/or modify
//  it under the terms of the GNU General Public License as published by
//  the Free Software Foundation, either version 3 of the License, or
//  (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful,
//  but WITHOUT ANY WARRANTY; without even the implied warranty of
//  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//  GNU General Public License for more details.
//
//  You should have received a copy of the GNU General Public License
//  along with this program.  If not, see <http://www.gnu.org/licenses/>.

import java.awt.Color;
import java.io.File;
import java.io.FilenameFilter;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.KeyListener;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseMoveListener;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;

public class Mandelbrot {

    private static final int MAX_ITERATIONS = 1024;
    private static final int BAILOUT_RADIUS = 2;
    private static final int TIMER_INTERVAL = 50;
    private static final DecimalFormat twoDecimalPlaces = new DecimalFormat("0.00");
    private static Color[] colors = new Color[128];
    
    double startX = -2;
    double endX = 1;
    double startY = -1;
    double endY = 1;
    
    private ExecutorService executor;

    private Image image;
    private Canvas canvas;
    private Shell shell;
    private MessageBox help;
    
    // for fly-through mode
    float velocity = 0;
    boolean generating = false;
    int mouseX;
    int mouseY;
    long leftButtonDown;
    long rightButtonDown;
    
    int screenCaptureIterator = 1;

    public static void main(String[] args) {
        new Mandelbrot().run();
    }

    public void run() {
        final Display display = new Display();
        shell = new Shell(display);
        shell.setSize(600, 400);
        shell.setText(setTitle());
        shell.setLayout(new FillLayout(SWT.VERTICAL));
        canvas = new Canvas(shell, SWT.NO_REDRAW_RESIZE | SWT.DOUBLE_BUFFERED);

        setScreenCaptureIterator();
        
        // setup some colors...
        Random r = new Random();
        for (int i=0; i < colors.length; i++)
            colors[i] = new Color(r.nextInt(255), r.nextInt(255), r.nextInt(255));
        
        executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        
        refreshImage();

        // event handlers...
        shell.addListener(SWT.Resize, new Listener() {
            public void handleEvent(Event e) {
                refreshImage();
                canvas.redraw();
            }
        });
        shell.addKeyListener(new KeyListener() {
            public void keyPressed(KeyEvent e) {
                if (e.character == 'p') {
                    ImageLoader imageLoader = new ImageLoader();
                    imageLoader.data = new ImageData[] {image.getImageData()};
                    imageLoader.save("image" + screenCaptureIterator + ".jpg", SWT.IMAGE_JPEG);
                    screenCaptureIterator++;
                } else if (e.character == 'h') {
                    help.open();
                } else if (e.character == SWT.CR) {
                    startX = -2;
                    endX = 1;
                    startY = -1;
                    endY = 1;
                    velocity = 0;
                    refreshImage();
                    canvas.redraw();
                } else if (e.character == ' ') {
                    velocity = 0;
                    leftButtonDown = 0;
                    rightButtonDown = 0;
                } else if (e.character == '=') {
                    velocity++;
                } else if (e.character == '-') {
                    velocity--;
                } else if (e.character == 'c') {
                    Random r = new Random();
                    for (int i=0; i < colors.length; i++)
                        colors[i] = new Color(r.nextInt(255), r.nextInt(255), r.nextInt(255));
                    refreshImage();
                    canvas.redraw();
                }
            }
            public void keyReleased(KeyEvent e) {}
        });
        canvas.addPaintListener(new PaintListener() {
            public void paintControl(PaintEvent e) {
                e.gc.drawImage(image, 0, 0);
            }
        });
        canvas.addMouseListener(new ZoomMouseListener());
        canvas.addMouseMoveListener(new MouseMoveListener() {
            public void mouseMove(MouseEvent e) {
                mouseX = e.x;
                mouseY = e.y;
            }
        });

        // animation loop...
        Runnable animThread = new Runnable() {
            public void run() {
                animate();
                display.timerExec(TIMER_INTERVAL, this);
            }
        };
        display.timerExec(TIMER_INTERVAL, animThread);
        
        // make visible and begin event loop...
        shell.open();
        help = new MessageBox(shell);
        help.setText("Instructions");
        help.setMessage("Move the pointer to where you want to zoom into.  Left click zooms in, the longer you hold down the mouse button the faster you go.\n"
                + "Right click works the same way, but zooms out.  Middle click stops moving completely.  Window is resizable.\n\n"
                + "Additional keyboard controls:\n\n"
                + "+\tmove inwards\n"
                + "-\tmove outwards\n"
                + "Space\tstop moving\n" 
                + "p\ttake a screenshot\n"
                + "Enter\treset view\n" 
                + "c\trandomize colors\n"
                + "h\tthis page\n\n"
                + "Email: kramer99@gmail.com for comments or questions.");
        help.open();
        while (!shell.isDisposed()) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }
        
        // close everything down...
        display.timerExec(-1, animThread);
        display.dispose();
        executor.shutdownNow();
    }

    private final class ZoomMouseListener extends MouseAdapter {
        public void mouseDown(MouseEvent e) {
            if (e.button == 1) {
                leftButtonDown = System.currentTimeMillis();
            } else if (e.button == 3) {
                rightButtonDown = System.currentTimeMillis();
            } else if (e.button == 2) {
                velocity = 0;
                leftButtonDown = 0;
                rightButtonDown = 0;
                shell.setText(setTitle());
            }
        }
        public void mouseUp(MouseEvent e) {
            if (e.button == 1) {
                leftButtonDown = 0;
            } else if (e.button == 3) {
                rightButtonDown = 0;
                // if velocity is close to 0, set to 0 to save CPU...
                if (Math.abs(velocity) < 0.05)
                    velocity = 0;
            }
        }
    }
    
    private void animate() 
    {
        /* If the width of the coordinate space decreases too close to the boundry of 
         * 64 bit precision decimal, we prevent further zooming inwards.
         * If the width is larger than 6, we prevent further zooming out, becuase there
         * is nothing interesting out there
         */
        if ((endX - startX < 0.0000000000004) && velocity != 0) {
            velocity = 0;
            leftButtonDown = 0;
        } else if ((endX - startX > 6) && velocity != 0) {
            velocity = 0;
            rightButtonDown = 0;
        } else {
            // work out velocity...
            if (leftButtonDown > 0) {
                velocity += (float)(System.currentTimeMillis() - leftButtonDown) / 5000;
            } else if (rightButtonDown > 0) {
                velocity -= (float)(System.currentTimeMillis() - rightButtonDown) / 5000;
            }
        
            // reposition view...
            if (!generating && velocity != 0) {
                generating = true;
                double xRatio = mouseX / (double)image.getImageData().width;
                double yRatio = mouseY / (double)image.getImageData().height;
                double xClickInComplexSpace = startX - ((startX - endX) * xRatio);
                double yClickInComplexSpace = startY - ((startY - endY) * yRatio);
                startX += (xClickInComplexSpace - startX) / (100/velocity);
                endX -= (endX - xClickInComplexSpace) / (100/velocity);
                startY += (yClickInComplexSpace - startY) / (100/velocity);
                endY -= (endY - yClickInComplexSpace) / (100/velocity);
    
                refreshImage();
                shell.setText(setTitle());
                canvas.redraw();
                generating = false;
            }
        }
    }

    private String setTitle() {
        return "Speed: " + twoDecimalPlaces.format(velocity) + ", Coordinates: [" + startX + "," + startY + "] to [" + endX + "," + endY + "]";
    }
    
    private void refreshImage() {
        // clear underlying OS resources, if they have been allocated
        if (image != null)
            image.dispose();
        
        PaletteData palette = new PaletteData(0xFF , 0xFF00 , 0xFF0000);
        ImageData imageData = new ImageData(shell.getClientArea().width, shell.getClientArea().height, 24, palette);
        mandelbrot(imageData);
        image = new Image(shell.getDisplay(), imageData);
    }

    private void mandelbrot(ImageData imageData) {
        double xRes = Math.abs(startX-endX) / (double)imageData.width;
        double yRes = Math.abs(startY-endY) / (double)imageData.height;
        double C_re = startX;
        double C_im = startY;
        for (int py = 0; py < imageData.height; py++) {
            
            executor.execute(new LineTask(imageData, C_re, C_im, xRes, py));
            
            C_re = startX;
            C_im += yRes;
        }
        
        // wait until all lines have been submitted to threads for rendering...
        while (!((ThreadPoolExecutor)executor).getQueue().isEmpty()) {
            try	{
                Thread.sleep(20);
            }
            catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private final class LineTask implements Runnable
    {
        private ImageData imageData;
        private double C_re;
        private double C_im;
        private double xRes;
        private int py;

        public LineTask(ImageData imageData, double C_re, double C_im, double xRes, int py) {
            this.imageData = imageData;
            this.C_re = C_re;
            this.C_im = C_im;
            this.xRes = xRes;
            this.py = py;
        }

        public void run() {
            for (int px = 0; px < imageData.width; px++) {
                C_re += xRes;
                EscapeResult result = escape(C_re, C_im);
                imageData.setPixel(px, py, getColor(result));
            }
        }
    }
    
    private EscapeResult escape(double C_re, double C_im) {
        int iteration = 0;
        double x = C_re;
        double y = C_im;
        while ((x*x + y*y <= (BAILOUT_RADIUS*BAILOUT_RADIUS)) && iteration < MAX_ITERATIONS) {
            double x_new = x * x - y * y + C_re;
            double y_new = 2 * x * y + C_im;
            x = x_new;
            y = y_new;
            iteration++;
        }
        return new EscapeResult(x, y, iteration);
    }
    
    private final class EscapeResult {
        public int iterations;
        public double Zn_re;
        public double Zn_im;
        public EscapeResult(double zn_im, double zn_re, int iterations)	{
            this.Zn_im = zn_im;
            this.Zn_re = zn_re;
            this.iterations = iterations;
        }
    }
    
    
    private int getColor(EscapeResult escape) {
        if (escape.iterations == MAX_ITERATIONS)
            return 0;
        else {
            // the 'normalized iteration count' algorithm...
            double abs = Math.sqrt(escape.Zn_re * escape.Zn_re + escape.Zn_im * escape.Zn_im);	// this is inefficient
            double mu = escape.iterations + 1 - Math.log(Math.log(abs)) / Math.log(BAILOUT_RADIUS);
            mu /= 16;	// ...this spreads the colors out more, looks nicer to me
            int iPart = (int)mu;
            double fPart = mu - iPart;
            int color = colorBlend(colors[iPart % colors.length], colors[(iPart+1) % colors.length], fPart);
            return color;
        }
    }
    
    private int colorBlend(Color a, Color b, double ratio) {
        if (a == null || b == null || ratio < 0 || ratio > 1)
            throw new IllegalArgumentException();
        int red = a.getRed() - (int)((a.getRed() - b.getRed()) * ratio);
        int green = a.getGreen() - (int)((a.getGreen() - b.getGreen()) * ratio);
        int blue = a.getBlue() - (int)((a.getBlue() - b.getBlue()) * ratio);
//		return red << 16 | green << 8 | blue;
        return blue << 16 | green << 8 | red;	// reversed for some reason
    }

    private void setScreenCaptureIterator()	{
        // find any saved screenshots so we don't overwrite them...
        File f = new File(".");
        File[] files = f.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return (name.startsWith("image") && name.endsWith(".jpg"));
            }
        });
        List<Integer> fileIndexes = new ArrayList<Integer>();
        for (File file: files) {
            String n = file.getName().substring(5, file.getName().length()-4);
            fileIndexes.add(Integer.valueOf(n));
        }
        if (fileIndexes.size() > 0)
            screenCaptureIterator = Collections.max(fileIndexes) + 1;
    }

}