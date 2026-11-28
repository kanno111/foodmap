package com.foodmap.restaurant.policy;

import com.foodmap.restaurant.dto.RestaurantSaveRequest;

import tools.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 使用与网页选项相同的区划全称，避免别名和县级市进入正式城市字段。 */
public final class RestaurantCityPolicy {
    private static final Set<String> NAMES = loadNames();

    private RestaurantCityPolicy() {}

    public static boolean isCanonical(String name) {
        return name != null && NAMES.contains(name);
    }

    /** 旧请求未发送城市时，只在坐标与已有快照一致的情况下沿用它。 */
    public static void retainForSameLocation(RestaurantSaveRequest input, JsonNode snapshot) {
        if (input.getCityName() != null || input.getLatitude() == null || input.getLongitude() == null
                || !snapshot.path("cityName").isTextual()) {
            return;
        }
        var latitude = snapshot.path("latitude");
        var longitude = snapshot.path("longitude");
        if (latitude.isNumber() && longitude.isNumber()
                && input.getLatitude().compareTo(latitude.decimalValue()) == 0
                && input.getLongitude().compareTo(longitude.decimalValue()) == 0) {
            input.setCityName(snapshot.path("cityName").asString());
        }
    }

    private static Set<String> loadNames() {
        try (var reader = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(RestaurantCityPolicy.class.getResourceAsStream(
                        "/restaurant/prefecture-city-names.txt")), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.toUnmodifiableSet());
        } catch (IOException error) {
            throw new IllegalStateException("Cannot load restaurant city directory", error);
        }
    }
}
