
package com.pa.lcr.lcp;

/**
 * Implémentation simple du bridge API -> DeliveryController.
 * Le serveur HTTP utilise cette façade pour accéder aux fonctions api_*.
 */
public final class DeliveryApiFacadeImpl implements ApiFacade {

    private final DeliveryController controller;

    public DeliveryApiFacadeImpl(DeliveryController controller) {
        this.controller = controller;
    }

    @Override
    public ApiResult api_scanUsb() {
        if (controller == null) {
            return ApiResult.fail(
                "Scan USB: 0 - Aucun registre détecté. Valide tes connexions au registre (câble/OTG/USB-C).",
                "NO_CONTROLLER"
            );
        }
        return controller.api_scanUsb();
    }

    @Override
    public ApiResult api_openPingUsb() {
        if (controller == null) {
            return ApiResult.fail(
                "Open/Ping USB: 0 - USB non prêt. Vérifie câble/permission.",
                "NO_CONTROLLER"
            );
        }
        return controller.api_openPingUsb();
    }

    @Override
    public ApiResult api_connectLcp() {
        if (controller == null) {
            return ApiResult.fail(
                "Connect LCP: 0 - USB non connecté.",
                "NO_CONTROLLER"
            );
        }
        return controller.api_connectLcp();
    }

    @Override
    public ApiResult api_deliveryStartC(int product1to16, double presetNet) {
        if (controller == null) {
            return ApiResult.fail(
                "Delivery C: 0 - Registre non prêt. Faire A d'abord.",
                "NO_CONTROLLER"
            );
        }
        return controller.api_deliveryStartC(product1to16, presetNet);
    }

    @Override
    public ApiResult api_deliveryJobGet(String jobId) {
        if (controller == null) {
            return ApiResult.fail("Job: 0 - Inconnu", "NO_CONTROLLER");
        }
        return controller.api_deliveryJobGet(jobId);
    }

    @Override
    public ApiResult api_deliveryOneShotStart(String numero_livraison, int product1to16, double presetNetL, String compartment) {
        if (controller == null) {
            return ApiResult.fail(
                "OneShot: 0 - Registre non prêt. Faire A d'abord.",
                "NO_CONTROLLER"
            );
        }
        return controller.api_deliveryOneShotStart(numero_livraison, product1to16, presetNetL, compartment);
    }

    @Override
    public ApiResult api_deliveryContinue(String jobId) {
        if (controller == null) {
            return ApiResult.fail(
                "Continue: 0 - Registre non prêt / non connecté.",
                "NO_CONTROLLER"
            );
        }
        return controller.api_deliveryContinue(jobId);
    }

    @Override
    public ApiResult api_deliveryTerminate(String jobId) {
        if (controller == null) {
            return ApiResult.fail(
                "Terminate: 0 - Registre non prêt / non connecté.",
                "NO_CONTROLLER"
            );
        }
        return controller.api_deliveryTerminate(jobId);
    }
}
