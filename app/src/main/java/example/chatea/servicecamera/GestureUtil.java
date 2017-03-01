package example.chatea.servicecamera;

import org.opencv.core.Rect;

/**
 * Created by bonet on 2017/2/24.
 */

public class GestureUtil {

    public static boolean startMonitor = false;

    // judge motion is true if over min shift
    private final int MIN_X_SHIFT = 100;
    private final int MIN_Y_SHIFT = 100;

    // min frames for check motion event
    private final int MIN_MOTION_FRAMES = 5;

    private static int posX = -100000;
    private static int posY = -100000;

    private static boolean moveUp = false;
    private static boolean moveDown = false;
    private static boolean moveLeft = false;
    private static boolean moveRight = false;
    private static boolean initPos = false;

    private static int passFrame = 0;



    public boolean checkRect(Rect rect){
        int shift = 0;

        if(!initPos) {
            posX = rect.x;
            posY = rect.y;
            initPos = true;
            return true;
        }

        if(posX > rect.x)
        {
            shift = posX - rect.x;

            if(shift > MIN_X_SHIFT) {
                moveLeft = true;
                moveRight = false;
            } else {
                moveLeft = false;
                moveRight = false;
            }
        } else {

            shift = rect.x - posX;

            if(shift > MIN_X_SHIFT) {
                moveLeft = false;
                moveRight = true;
            } else {
                moveLeft = false;
                moveRight = false;
            }
        }


        if(posY > rect.y)
        {
            shift = posY - rect.y;

            if(shift > MIN_Y_SHIFT) {
                moveUp = false;
                moveDown = true;

                // refresh position
                posX = rect.x;
                posY = rect.y;
            } else {
                moveUp = false;
                moveDown = false;
            }
        } else {

            shift = rect.y - posY;

            if(shift > MIN_Y_SHIFT) {
                moveUp = true;
                moveDown = false;

                // refresh position
                posX = rect.x;
                posY = rect.y;
            } else {
                moveUp = false;
                moveDown = false;
            }
        }

        return true;
    }

    public boolean isMoveRight()
    {
        return moveRight;
    }

    public boolean isMoveLeft() {
        return moveLeft;
    }

    public boolean isMoveDown() {
        return moveDown;
    }

    public boolean isMoveUp() {
        return moveUp;
    }

    public void checkPassFrame()
    {
        if(passFrame++ > MIN_MOTION_FRAMES)
        {
            initPos = false;
            passFrame = 0;
        }
    }

    public void setStartMonitor()
    {
        startMonitor = true;
    }

    public void setStopMonitor()
    {
        startMonitor = false;
        initPos = false;
    }
}
