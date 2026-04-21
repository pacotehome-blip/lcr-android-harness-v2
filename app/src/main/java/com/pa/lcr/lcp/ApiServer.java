package com.pa.lcr.lcp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Pattern;

public class ApiServer {

    private static final String BASE_URL = "http://example.com/api/";

    // Method to validate jobId
    public boolean validateJobId(String jobId) {
        // Example validation: jobId must not be null, empty and must match a specific pattern
        return jobId != null && !jobId.isEmpty() && Pattern.matches("^[a-zA-Z0-9_-]+$, jobId);
    }

    // Method to make an HTTP GET request
    public String getApiResponse(String endpoint) throws Exception {
        StringBuilder response = new StringBuilder();
        URL url = new URL(BASE_URL + endpoint);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");

        // Check the response code
        int responseCode = connection.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) { // success
            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();
        } else {
            throw new RuntimeException("Failed : HTTP error code : " + responseCode);
        }
        return response.toString();
    }

    // Method to handle other HTTP operations or parsing can be added here
}