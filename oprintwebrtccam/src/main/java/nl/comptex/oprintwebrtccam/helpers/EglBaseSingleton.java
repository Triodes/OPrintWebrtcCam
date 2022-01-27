package nl.comptex.oprintwebrtccam.helpers;

import org.webrtc.EglBase;

public class EglBaseSingleton {
    private static EglBase eglBase;

    public static EglBase getEglBase() {
        if (eglBase == null)
            eglBase = EglBase.create();
        return eglBase;
    }

    public static void release() {
        eglBase.release();
        eglBase = null;
    }
}
