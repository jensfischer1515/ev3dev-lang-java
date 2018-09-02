package ev3dev.hardware.display;

import com.sun.jna.LastErrorException;
import ev3dev.hardware.display.spi.FramebufferProvider;
import ev3dev.utils.AllImplFailedException;
import ev3dev.utils.io.*;
import lombok.extern.slf4j.Slf4j;
import sun.misc.Signal;
import sun.misc.SignalHandler;

import java.io.Closeable;
import java.io.IOException;

import static ev3dev.utils.io.NativeConstants.*;

/**
 * <p>System console manager.</p>
 *
 * <p>It manages the output mode of the display. It is possible
 * to switch between graphics mode and text mode. Graphics mode reserves
 * the display for drawing operation and hides text output. On the
 * other hand, text mode suspends drawing operations and shows the text
 * on the Linux console.
 * This class also manages VT (= Virtual Terminal = console) switches
 * in the case of a VT switch occuring when graphics mode is set.</p>
 *
 * <p>Implementation of this class is based on the GRX3 linuxfb plugin.</p>
 *
 * @author Jakub Vaněk
 * @since 2.4.7
 */
@Slf4j
public class SystemDisplay implements DisplayInterface, Closeable {
    private static DisplayInterface instance = null;
    private ILibc libc;
    private String fbPath = null;
    private JavaFramebuffer fbInstance = null;
    private NativeTTY ttyfd = null;
    private boolean gfx_active = false;
    private int old_kbmode;
    private Thread deinitializer;
    private SignalHandler oldSignaller;

    /**
     * <p>Initialize the display, register event handlers and switch to text mode.</p>
     *
     * @throws RuntimeException when initialization or mode switch fails.
     */
    private SystemDisplay(ILibc libc) {
        this.libc = libc;
        try {
            LOGGER.trace("Initialing system console");
            initialize();
            oldSignaller = Signal.handle(new Signal("USR2"), this::console_switch_handler);
            deinitializer = new Thread(this::deinitialize, "console restore");
            Runtime.getRuntime().addShutdownHook(deinitializer);
            switchToTextMode();
        } catch (IOException e) {
            LOGGER.debug("System console initialization failed");
            throw new RuntimeException("Error initializing system console", e);
        }
    }

    /**
     * <p>Get the system display.</p>
     * <p>The console number the system display will be on is
     * the same as the one this program is started on.</p>
     *
     * @return Reference to the display this program was opened on.
     */
    public static synchronized DisplayInterface getInstance() {
        if (instance == null) {
            ILibc libc = new NativeLibc();
            try {
                instance = new SystemDisplay(libc);
            } catch (RuntimeException e) {
                if (e.getCause() instanceof LastErrorException &&
                        ((LastErrorException) e.getCause()).getErrorCode() == NativeConstants.ENOTTY) {
                    LOGGER.debug("but the failure was caused by not having a real TTY, using fake console");
                    // we do not run from Brickman
                    instance = new FakeDisplay(libc);
                } else {
                    throw e;
                }
            }
        }
        return instance;
    }

    public static DisplayInterface createMockedInstance(ILibc libc) {
        if (libc instanceof NativeLibc) {
            throw new IllegalArgumentException("Mocking is not allowed with native libc!");
        }
        return new SystemDisplay(libc);
    }

    public static DisplayInterface createMockedFakeInstance(ILibc libc) {
        if (libc instanceof NativeLibc) {
            throw new IllegalArgumentException("Mocking is not allowed with native libc!");
        }
        return new FakeDisplay(libc);
    }

    /**
     * <p>Initialize the display state.</p>
     *
     * <p>Opens the current VT, saves keyboard mode and
     * identifies the appropriate framebuffer device.</p>
     *
     * @throws IOException When the initialization fails.
     */
    private void initialize() throws IOException {
        NativeFramebuffer fbfd = null;
        boolean success = false;
        try {
            LOGGER.trace("Opening TTY");
            ttyfd = new NativeTTY("/dev/tty", O_RDWR, libc);
            int activeVT = ttyfd.getVTstate().v_active;
            old_kbmode = ttyfd.getKeyboardMode();

            LOGGER.trace("Opening FB 0");
            fbfd = new NativeFramebuffer("/dev/fb0", libc);
            int fbn = fbfd.mapConsoleToFramebuffer(activeVT);
            LOGGER.trace("map vt{} -> fb{}", activeVT, fbn);

            if (fbn < 0) {
                LOGGER.error("No framebuffer for current TTY");
                throw new IOException("No framebuffer device for the current VT");
            }
            fbPath = "/dev/fb" + fbn;
            if (fbn != 0) {
                LOGGER.trace("Redirected to FB {}", fbn);
                fbfd.close();
                fbfd = new NativeFramebuffer(fbPath, libc);
            }

            success = true;
        } finally {
            if (fbfd != null) {
                fbfd.close();
            }
            if (!success) {
                ttyfd.close();
            }
        }
    }

    @Override
    public void close() {
        if (ttyfd.isOpen()) {
            deinitialize();
            Runtime.getRuntime().removeShutdownHook(deinitializer);
            Signal.handle(new Signal("USR2"), oldSignaller);
        }
    }

    /**
     * <p>Put the display to a state where it is ready for returning.</p>
     *
     * <p>Keyboard mode is restored, text mode is set and VT autoswitch is enabled.
     * Then, console file descriptor is closed.</p>
     */
    private void deinitialize() {
        LOGGER.trace("Closing system console");
        try {
            ttyfd.setKeyboardMode(old_kbmode);
            ttyfd.setConsoleMode(KD_TEXT);

            NativeTTY.vt_mode vtm = new NativeTTY.vt_mode();
            vtm.mode = VT_AUTO;
            vtm.relsig = 0;
            vtm.acqsig = 0;
            ttyfd.setVTmode(vtm);

            ttyfd.close();

        } catch (LastErrorException e) {
            System.err.println("Error occured during console shutdown: " + e.getMessage());
            e.printStackTrace();
        }
        gfx_active = false;
    }

    /**
     * <p>Switch the display to a graphics mode.</p>
     *
     * <p>It switches VT to graphics mode with keyboard turned off.
     * Then, it tells kernel to notify Java when VT switch occurs.
     * Also, framebuffer contents are restored and write access is enabled.</p>
     *
     * @throws RuntimeException when the switch fails
     */
    public void switchToGraphicsMode() {
        LOGGER.trace("Switching console to graphics mode");
        try {
            ttyfd.setKeyboardMode(K_OFF);
            ttyfd.setConsoleMode(KD_GRAPHICS);

            NativeTTY.vt_mode vtm = new NativeTTY.vt_mode();
            vtm.mode = VT_PROCESS;
            vtm.relsig = SIGUSR2;
            vtm.acqsig = SIGUSR2;
            ttyfd.setVTmode(vtm);
        } catch (LastErrorException e) {
            throw new RuntimeException("Switch to graphics mode failed", e);
        }

        gfx_active = true;
        if (fbInstance != null) {
            fbInstance.restoreData();
            fbInstance.setFlushEnabled(true);
        }
    }

    /**
     * <p>Switch the display to a text mode.</p>
     *
     * <p>It stores framebuffer data and disables write access. Then,
     * it switches VT to text mode and allows kernel to auto-switch it.</p>
     *
     * @throws RuntimeException when the switch fails
     */
    public void switchToTextMode() {
        LOGGER.trace("Switching console to text mode");
        if (fbInstance != null) {
            fbInstance.setFlushEnabled(false);
            fbInstance.storeData();
        }
        try {
            ttyfd.setConsoleMode(KD_TEXT);

            NativeTTY.vt_mode vtm = new NativeTTY.vt_mode();
            vtm.mode = VT_AUTO;
            vtm.relsig = 0;
            vtm.acqsig = 0;
            ttyfd.setVTmode(vtm);
        } catch (LastErrorException e) {
            throw new RuntimeException("Switch to text mode failed", e);
        }

        gfx_active = false;
    }

    /**
     * <p>Release ownership of our VT.</p>
     * <p>Stores framebuffer contents and disables write access.
     * Then, it allows kernel to switch the VT.</p>
     */
    private void vt_release() {
        LOGGER.trace("Releasing VT");
        if (fbInstance != null) {
            fbInstance.setFlushEnabled(false);
            fbInstance.storeData();
        }
        try {
            ttyfd.signalSwitch(1);
        } catch (LastErrorException e) {
            System.err.println("Error occured during VT switch: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * <p>Take ownership of our VT.</p>
     * <p>Acknowledges VT transition, enables graphics mode and disables keyboard.
     * Then, it restores framebuffer contents and enables write access.</p>
     */
    private void vt_acquire() {
        LOGGER.trace("Acquiring VT");
        try {
            ttyfd.signalSwitch(VT_ACKACQ);
            ttyfd.setKeyboardMode(K_OFF);
            ttyfd.setConsoleMode(KD_GRAPHICS);
        } catch (LastErrorException e) {
            System.err.println("Error occured during VT switch: " + e.getMessage());
            e.printStackTrace();
        }
        if (fbInstance != null) {
            fbInstance.restoreData();
            fbInstance.setFlushEnabled(true);
        }
    }

    /**
     * <p>Handle VT switch signal.</p>
     *
     * @param sig unused argument for SignalHandler compatibility
     */
    private void console_switch_handler(Signal sig) {
        LOGGER.trace("VT switch handler called");
        if (gfx_active) {
            vt_release();
            gfx_active = false;
        } else {
            vt_acquire();
            gfx_active = true;
        }
    }

    /**
     * <p>Get the framebuffer for the system display.</p>
     *
     * <p>The framebuffer is initialized only once, later calls
     * return references to the same instance.</p>
     *
     * @return Java framebuffer compatible with the system display.
     * @throws RuntimeException when switch to graphics mode or the framebuffer initialization fails.
     */
    public synchronized JavaFramebuffer openFramebuffer() {
        if (fbInstance == null) {
            LOGGER.debug("Initialing framebuffer in system console");
            switchToGraphicsMode();
            try {
                NativeFramebuffer fbfd = new NativeFramebuffer(fbPath, libc);
                fbInstance = FramebufferProvider.load(fbfd);
            } catch (AllImplFailedException e) {
                throw new RuntimeException("System framebuffer opening failed", e);
            }
            fbInstance.setFlushEnabled(gfx_active);
            fbInstance.clear();
            fbInstance.storeData();
        }
        return fbInstance;
    }

    /**
     * Class to allow running programs over SSH
     *
     * @author Jakub Vaněk
     * @since 2.4.7
     */
    @Slf4j
    private static class FakeDisplay implements DisplayInterface {
        private JavaFramebuffer fbInstance = null;
        private ILibc libc;

        /**
         * noop
         */
        private FakeDisplay(ILibc libc) {
            this.libc = libc;
        }

        /**
         * noop, graphics goes to the display
         */
        @Override
        public void switchToGraphicsMode() {
            LOGGER.trace("Fake switch to graphics mode");
        }

        /**
         * noop, text goes to SSH host
         */
        @Override
        public void switchToTextMode() {
            LOGGER.trace("Fake switch to text mode");
        }

        @Override
        public synchronized JavaFramebuffer openFramebuffer() {
            if (fbInstance == null) {
                LOGGER.debug("Initialing framebuffer in fake console");
                Brickman.disable();
                try {
                    NativeFramebuffer fbfd = new NativeFramebuffer("/dev/fb0", libc);
                    fbInstance = FramebufferProvider.load(fbfd);
                } catch (AllImplFailedException e) {
                    throw new RuntimeException("System framebuffer opening failed", e);
                }
                fbInstance.setFlushEnabled(true);
                fbInstance.clear();
                fbInstance.storeData();
            }
            return fbInstance;
        }
    }
}
