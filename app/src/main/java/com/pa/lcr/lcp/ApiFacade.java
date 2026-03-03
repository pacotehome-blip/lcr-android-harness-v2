
package com.pa.lcr.lcp;

/**
 * API-Face: contrat entre ApiServer et la logique métier.
 *
 * Important:
 * - Le serveur HTTP ne parle jamais directement au transport (LcpLink).
 * - Tout passe par DeliveryController (source de vérité).
 */
public interface ApiFacade {

    // USB
    ApiResult api_scanUsb();
    ApiResult api_openPingUsb();

    // LCP (décision A/C basée sur 0x28)
    ApiResult api_connectLcp();

    // Delivery (C + job polling)
    ApiResult api_deliveryStartC(int product1to16, double presetNet);
    ApiResult api_deliveryJobGet(String jobId);

    // Delivery (OneShot + controls)
    ApiResult api_deliveryOneShotStart(String numero_livraison, int product1to16, double presetNetL, String compartment);
    ApiResult api_deliveryContinue(String jobId);
    ApiResult api_deliveryTerminate(String jobId);

}
