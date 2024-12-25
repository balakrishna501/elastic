package com.opensearch.demo.elasticapi.service;

import com.google.gson.*;
import com.opensearch.demo.elasticapi.dto.ElasticRequestDTO;
import org.opensearch.client.opensearch.core.GetResponse;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map.Entry;
import java.util.Set;

@Service("elasticResponseFormatter")
public class ElasticResponseFormatter {
	private static final Logger LOGGER = LoggerFactory.getLogger(ElasticResponseFormatter.class);

	public JsonObject convertSearchResponseToJsonObj(SearchResponse searchResponse, ElasticRequestDTO elasticRequestDTO) {
		JsonObject response = new JsonObject();
		JsonArray emptyResponse = new JsonArray();
		try {
			if (searchResponse.aggregations() != null || searchResponse.hits().hits().size() >= 1) {
				JsonObject searchJson = new JsonParser().parse(searchResponse.toString()).getAsJsonObject();
				if (searchJson.has("aggregations")) {
					response.add("aggregations", searchJson.get("aggregations").getAsJsonObject());
				}
				if (searchJson.has("hits")) {
					JsonArray hits = searchJson.get("hits").getAsJsonObject().get("hits").getAsJsonArray();
					JsonArray resultJson = new JsonArray();
					for (JsonElement hit : hits) {
						if (hit.getAsJsonObject().has("_source")) {
							resultJson.add(hit.getAsJsonObject().get("_source"));
						}
					}
					response.add("hits", resultJson);
				}
			} else if (!response.has("hits")) {
				response.add("hits", emptyResponse);
			} else if (!response.has("aggregations")) {
				response.add("aggregations", emptyResponse);
			}
		} catch (Exception e) {
			LOGGER.error("Exception in Converting json", e);
		}
		return response;
	}

//	private double getDistanceInKm(double lat,double lon,double baseLat, double baseLog,String unit){
//		 return Math.round(GeoDistance.ARC.calculate(lat,lon, baseLat, baseLog, DistanceUnit.fromString(unit)) * 100.0) / 100.0;
//	}
}
