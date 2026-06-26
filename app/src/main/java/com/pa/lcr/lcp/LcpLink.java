// ============================================================
// AJOUT DANS LcpLink.java — insérer dans la section "STRUCTURES PUBLIQUES"
// juste après la classe MachineStatus
// ============================================================

    /** Info d'un produit lue depuis le registre (message 0x00). */
    public static final class ProductInfo {
        public final int productId; // index 0-based tel que retourné par le registre
        public final String name;   // description ASCII, trimée
        public ProductInfo(int productId, String name) {
            this.productId = productId;
            this.name = (name != null) ? name : "";
        }
        @Override public String toString() {
            return "Produit " + (productId + 1) + ": " + name;
        }
    }

// ============================================================
// AJOUT DANS LcpLink.java — insérer dans la section "OPS PUBLIQUES"
// après opSyncDateTime()
// ============================================================

    /**
     * Lit le nom du produit ACTIF via le message LCP 0x00 (GET_PRODUCT_INFO).
     * Retourne rc + productId (0-based) + description ASCII.
     *
     * Réponse payload: [rc, productId, name_bytes...]
     */
    public ProductInfo opGetProductInfo() throws IOException {
        // Message type 0x00 — payload = [0x00] (single byte, pas de MSG_GET_FIELD)
        Response r = sendRecv(new byte[]{0x00}, 5000);
        ensureOk(r, "GET_PRODUCT_INFO");
        int productId = (r.payload.length > 1) ? (r.payload[1] & 0xFF) : 0;
        String name = "";
        if (r.payload.length > 2) {
            byte[] nb = new byte[r.payload.length - 2];
            System.arraycopy(r.payload, 2, nb, 0, nb.length);
            name = new String(nb, java.nio.charset.StandardCharsets.US_ASCII)
                       .replace("\0", "").trim();
        }
        return new ProductInfo(productId, name);
    }

    /**
     * Scanne les 16 produits du registre.
     * Pour chaque index 0..15 : SET_FIELD #0 → GET_PRODUCT_INFO → mémoriser le nom.
     * Restaure le produit actif original à la fin (même en cas d'erreur).
     *
     * @param progressLog callback pour logguer la progression (nullable)
     * @return Map<Integer, String> : index 0-based → description (jamais null)
     * @throws IOException si la communication échoue
     */
    public java.util.Map<Integer, String> opScanAllProductNames(
            android.util.Consumer<String> progressLog) throws IOException {

        // Sauvegarder le produit actif courant
        byte[] curRaw = opGetField(0);
        int originalIdx = (curRaw != null && curRaw.length > 0) ? (curRaw[0] & 0xFF) : 0;

        java.util.LinkedHashMap<Integer, String> result = new java.util.LinkedHashMap<>();
        try {
            for (int idx = 0; idx < 16; idx++) {
                // Basculer vers ce produit
                opSetField(0, new byte[]{(byte) idx});
                // Petit délai — le registre peut avoir besoin d'un moment après SET
                try { Thread.sleep(80); } catch (Exception ignored) {}
                // Lire le nom
                ProductInfo pi = opGetProductInfo();
                result.put(idx, pi.name);
                if (progressLog != null) {
                    progressLog.accept("Produit " + (idx + 1) + ": " + pi.name);
                }
            }
        } finally {
            // Toujours restaurer le produit original
            try {
                opSetField(0, new byte[]{(byte) originalIdx});
            } catch (Exception ignored) {
                android.util.Log.w("LcpLink",
                    "opScanAllProductNames: échec restauration produit " + originalIdx);
            }
        }
        return result;
    }
   
