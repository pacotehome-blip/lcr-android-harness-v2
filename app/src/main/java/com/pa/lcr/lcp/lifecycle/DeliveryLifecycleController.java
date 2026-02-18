
package com.pa.lcr.lcp.lifecycle;

import com.pa.lcr.lcp.util.LifecycleLogger;

/**
 * DeliveryLifecycleController
 *
 * Garde-fou applicatif :
 * - n'envoie aucune commande LCP
 * - bloque uniquement les actions incohérentes avec le protocole
 */
public class DeliveryLifecycleController {

    private DeliveryLifecycle state = DeliveryLifecycle.IDLE;
    private final LifecycleLogger logger;

    public DeliveryLifecycleController(LifecycleLogger logger) {
        this.logger = logger;
    }

    public DeliveryLifecycle getState() {
        return state;
    }

    private void transition(DeliveryLifecycle to, String reason) {
        logger.info("DeliveryLifecycle", state + " → " + to + " (" + reason + ")");
        state = to;
    }

    public boolean allowStart(boolean ticketPending) {
        if (state == DeliveryLifecycle.IDLE) {
            if (ticketPending) {
                logger.error("DeliveryLifecycle", "START bloqué : ticketPending=true");
                return false;
            }
            transition(DeliveryLifecycle.PRESTART, "Start demandé");
            return true;
        }
        logger.error("DeliveryLifecycle", "START bloqué en état " + state);
        return false;
    }

    public void onPrestartConfirmed() {
        if (state == DeliveryLifecycle.PRESTART) {
            transition(DeliveryLifecycle.STARTING, "Prestart confirmé");
        } else {
            logger.warn("DeliveryLifecycle", "PrestartConfirm ignoré en état " + state);
        }
    }

    public boolean allowCmd0(Cmd0Usage usage) {
        switch (state) {
            case STARTING:
                return usage == Cmd0Usage.START;
            case PAUSED:
                return usage == Cmd0Usage.RESUME;
            default:
                logger.error("DeliveryLifecycle", "Cmd#0 (" + usage + ") bloqué en état " + state);
                return false;
        }
    }

    public void onStartConfirmed(boolean begin) {
        if (state == DeliveryLifecycle.STARTING && begin) {
            transition(DeliveryLifecycle.ACTIVE, "START confirmé (begin=true)");
        } else if (state == DeliveryLifecycle.STARTING) {
            logger.warn("DeliveryLifecycle", "START non confirmé (begin=false)");
        }
    }

    public void onPauseDetected() {
        if (state == DeliveryLifecycle.ACTIVE) {
            transition(DeliveryLifecycle.PAUSED, "Pause confirmée");
        }
    }

    public boolean allowResume() {
        if (state == DeliveryLifecycle.PAUSED) {
            transition(DeliveryLifecycle.ACTIVE, "Resume demandé");
            return true;
        }
        logger.error("DeliveryLifecycle", "RESUME bloqué en état " + state);
        return false;
    }

    public boolean allowEnd() {
        if (state == DeliveryLifecycle.ACTIVE || state == DeliveryLifecycle.PAUSED) {
            transition(DeliveryLifecycle.ENDING, "END demandé");
            return true;
        }
        logger.error("DeliveryLifecycle", "END bloqué en état " + state);
        return false;
    }

    public void onEndConfirmed() {
        if (state == DeliveryLifecycle.ENDING) {
            transition(DeliveryLifecycle.ENDED, "END confirmé");
        }
    }

    public void onResetSyncCompleted() {
        if (state == DeliveryLifecycle.ENDED) {
            transition(DeliveryLifecycle.IDLE, "Reset/Sync terminé");
        }
    }

    /** Timeout: règle dure -> ne change jamais l'état */
    public void onTimeout(String context) {
        logger.error("DeliveryLifecycle", "Timeout dans " + context + " (état=" + state + ")");
    }
}
