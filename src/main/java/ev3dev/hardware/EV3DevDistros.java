package ev3dev.hardware;

import ev3dev.utils.Shell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EV3DevDistros {

    private static final Logger LOGGER = LoggerFactory.getLogger(EV3DevDistros.class);

    private static final String DEBIAN_DISTRO_DETECTION_QUERY = "cat /etc/os-release";
    private static final String JESSIE_DISTRO_DETECTION_PATTERN = "ev3dev-jessie";
    private static final String STRETCH_DISTRO_DETECTION_PATTERN = "ev3dev-stretch";
    private static final String DEBIAN_DISTRO_DETECTION_KEY = "EV3DEV_DISTRO";
    private static final String DEBIAN_DISTRO_DETECTION_JESSIE = "jessie";
    private static final String DEBIAN_DISTRO_DETECTION_STRETCH = "stretch";

    private EV3DevDistro CURRENT_DISTRO;

    public EV3DevDistros() {

        final String osResult = Shell.execute(DEBIAN_DISTRO_DETECTION_QUERY);
        if (osResult.contains(JESSIE_DISTRO_DETECTION_PATTERN)) {
            setJessie();
        } else if (osResult.contains(STRETCH_DISTRO_DETECTION_PATTERN)) {
            setStretch();
        } else {
            String value = System.getProperty(DEBIAN_DISTRO_DETECTION_KEY);
            if (value != null) {
                switch (value) {
                    case DEBIAN_DISTRO_DETECTION_JESSIE:
                        setJessie();
                        return;
                    case DEBIAN_DISTRO_DETECTION_STRETCH:
                        setStretch();
                        return;
                }
            }
            LOGGER.warn("Failed to detect distro, falling back to Stretch.");
            setStretch();
        }
    }

    private void setStretch() {
        LOGGER.debug("Debian Stretch detected");
        CURRENT_DISTRO = EV3DevDistro.STRETCH;
    }

    private void setJessie() {
        LOGGER.debug("Debian Jessie detected");
        CURRENT_DISTRO = EV3DevDistro.JESSIE;
    }

    public EV3DevDistro getDistro() {
        return CURRENT_DISTRO;
    }

}