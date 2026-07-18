package com.voltx.evgenee.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltx.evgenee.configuration.RedisConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;


@Service
@Slf4j
public class GeocodingService {

    private static final double BHOPAL_LON = 77.4126;
    private static final double BHOPAL_LAT = 23.2599;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public GeocodingService() {
        this.restClient = RestClient.builder().build();
        this.objectMapper = new ObjectMapper();
    }

    @Cacheable(cacheNames = RedisConfig.GEOCODING, key = "#locationStr == null ? 'blank' : #locationStr.trim().toLowerCase()")
    public double[] geocodeLocation(String locationStr) {
        try {
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
                return coords;
            }
        } catch (Exception e) {
            log.error("Geocoding error: {}", e.getMessage());
        }
        return new double[]{BHOPAL_LON, BHOPAL_LAT};
    }

    @Cacheable(cacheNames = RedisConfig.REVERSE_GEOCODING, key = "T(java.lang.String).format('%.5f:%.5f', #lat, #lng)")
    public String reverseGeocode(double lat, double lng) {
        try {
            String url = "https://nominatim.openstreetmap.org/reverse?lat=" + lat + "&lon=" + lng + "&format=json";
            String body = restClient.get()
                    .uri(url)
                    .header("User-Agent", "EvGenee_Bot")
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(body);
            if (node != null && node.has("display_name")) {
                String address = node.get("display_name").asText();
                return address;
            }
        } catch (Exception e) {
            log.error("Reverse geocoding error: {}", e.getMessage());
        }
        return "Bhopal, Madhya Pradesh, India";
    }

    @Cacheable(cacheNames = RedisConfig.ROAD_DISTANCE, key = "T(java.lang.String).format('%.5f:%.5f:%.5f:%.5f', #startCoords[0], #startCoords[1], #endCoords[0], #endCoords[1])")
    public RoadInfo getRoadDistance(double[] startCoords, double[] endCoords) {
        try {
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
