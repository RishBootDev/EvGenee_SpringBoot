package com.voltx.evgenee.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class GeocodingService {

    private static final double BHOPAL_LON = 77.4126;
    private static final double BHOPAL_LAT = 23.2599;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Map<String, double[]> geocodeCache = new ConcurrentHashMap<>();
    private final Map<String, String> reverseGeocodeCache = new ConcurrentHashMap<>();
    private final Map<String, RoadInfo> roadDistanceCache = new ConcurrentHashMap<>();

    public GeocodingService() {
        this.restClient = RestClient.builder().build();
        this.objectMapper = new ObjectMapper();
    }

    public double[] geocodeLocation(String locationStr) {
        try {
            String cacheKey = locationStr.trim().toLowerCase();
            if (geocodeCache.containsKey(cacheKey)) {
                return geocodeCache.get(cacheKey);
            }
            String url = "https://nominatim.openstreetmap.org/search?q="
                    + locationStr.trim().replace(" ", "+")
                    + "&format=json&limit=1";
            String body = restClient.get()
                    .uri(url)
                    .header("User-Agent", "EvGenee_Bot")
                    .retrieve()
                    .body(String.class);
            JsonNode arr = objectMapper.readTree(body);
            if (arr != null && arr.isArray() && arr.size() > 0) {
                double lon = arr.get(0).get("lon").asDouble();
                double lat = arr.get(0).get("lat").asDouble();
                double[] coords = {lon, lat};
                geocodeCache.put(cacheKey, coords);
                return coords;
            }
        } catch (Exception e) {
            log.error("Geocoding error: {}", e.getMessage());
        }
        return new double[]{BHOPAL_LON, BHOPAL_LAT};
    }

    public String reverseGeocode(double lat, double lng) {
        try {
            String cacheKey = lat + "," + lng;
            if (reverseGeocodeCache.containsKey(cacheKey)) {
                return reverseGeocodeCache.get(cacheKey);
            }
            String url = "https://nominatim.openstreetmap.org/reverse?lat=" + lat + "&lon=" + lng + "&format=json";
            String body = restClient.get()
                    .uri(url)
                    .header("User-Agent", "EvGenee_Bot")
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(body);
            if (node != null && node.has("display_name")) {
                String address = node.get("display_name").asText();
                reverseGeocodeCache.put(cacheKey, address);
                return address;
            }
        } catch (Exception e) {
            log.error("Reverse geocoding error: {}", e.getMessage());
        }
        return "Bhopal, Madhya Pradesh, India";
    }

    public RoadInfo getRoadDistance(double[] startCoords, double[] endCoords) {
        try {
            String cacheKey = startCoords[0] + "," + startCoords[1] + "|" + endCoords[0] + "," + endCoords[1];
            if (roadDistanceCache.containsKey(cacheKey)) {
                return roadDistanceCache.get(cacheKey);
            }
            String url = "http://router.project-osrm.org/route/v1/driving/"
                    + startCoords[0] + "," + startCoords[1] + ";"
                    + endCoords[0] + "," + endCoords[1] + "?overview=false";
            String body = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(body);
            if (node != null && node.has("routes") && node.get("routes").size() > 0) {
                JsonNode route = node.get("routes").get(0);
                double distanceKm = Math.round(route.get("distance").asDouble() / 10.0) / 100.0;
                double durationMins = Math.round(route.get("duration").asDouble() / 6.0) / 10.0;
                RoadInfo info = new RoadInfo(distanceKm, durationMins);
                roadDistanceCache.put(cacheKey, info);
                return info;
            }
        } catch (Exception e) {
            log.error("OSRM error: {}", e.getMessage());
        }
        double distanceKm = haversine(startCoords[1], startCoords[0], endCoords[1], endCoords[0]);
        return new RoadInfo(Math.round(distanceKm * 100.0) / 100.0, Math.round(distanceKm * 1.5 * 10.0) / 10.0);
    }

    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371 * (2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)));
    }

    public record RoadInfo(double distanceKm, double durationMins) {}
}
