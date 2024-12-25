package com.opensearch.demo.elasticapi.service;

import com.google.gson.JsonObject;
import com.opensearch.demo.elasticapi.dto.*;
import com.opensearch.demo.elasticapi.enumeration.ElasticOperations;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.SortOptions;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch._types.aggregations.Aggregation;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.ChildScoreMode;
import org.opensearch.client.opensearch._types.query_dsl.FuzzyQuery;
import org.opensearch.client.opensearch._types.query_dsl.QueryBuilders;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import org.opensearch.client.opensearch.core.search.SourceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository("elasticQueryBuilder")
public class ElasticQueryBuilder {
	private static Logger LOGGER = LoggerFactory.getLogger(ElasticQueryBuilder.class);
	static OpenSearchClient client;
	@Autowired
	ElasticConnectionFactory elasticConnectionFactory;
	@Autowired
	ElasticResponseFormatter elasticResponseFormatter;

	@Autowired
	@Qualifier("elasticSearchUtils")
	ElasticSearchUtils elasticSearchUtils;

	public ElasticResponseDTO prepareQueryTemplate(ElasticRequestDTO elasticRequest) {
		ElasticResponseDTO elasticResponseDTO = new ElasticResponseDTO();
		String sortKey = null;
		SortOrder sortOrder = SortOrder.Desc;
		try {
			if (elasticRequest != null && elasticRequest.getCriteria() != null) {

				if (elasticRequest.getPage() == null) {
					elasticRequest.setPage(1);
				}
				if (elasticRequest.getLimit() == null) {
					elasticRequest.setLimit(15);
				}

				SearchRequest searchRequest = buildQueryFromRequest(elasticRequest);
				if (searchRequest != null) {
					JsonObject queryObjTemplate = elasticSearchUtils.getqueryTemplalteObj(elasticRequest.getCriteria().getQueryName());
					String index = queryObjTemplate.get("index").getAsString();
					LOGGER.info("Index Search for " + index);
					if (queryObjTemplate.has("sortKey")) {
						sortKey = queryObjTemplate.get("sortKey").getAsString();
					}
					if (queryObjTemplate.has("sortOrder") && queryObjTemplate.get("sortOrder") != null) {
						sortOrder = SortOrder.valueOf(queryObjTemplate.get("sortOrder").getAsString().toUpperCase());
					}
					LOGGER.info("Executing Search for " + searchRequest);

					// Build the final search request with index and pagination settings
					SearchRequest finalSearchRequest = searchRequest;
					String finalSortKey = sortKey;
					SortOrder finalSortOrder = sortOrder;
					searchRequest = SearchRequest.of(sr -> sr
							.index(index)
							.query(finalSearchRequest.query())
							.from((elasticRequest.getPage() - 1) * elasticRequest.getLimit())
							.size(elasticRequest.getLimit())
							.sort(finalSortKey != null ? SortOptions.of(s -> s
									.field(f -> f
											.field(finalSortKey)
											.order(finalSortOrder)
									)
							) : null)
					);

					SearchResponse<Object> esResponse = client.search(searchRequest, Object.class);
					if (LOGGER.isDebugEnabled()) {
						LOGGER.info("Executing Search for " + esResponse);
					}
					if (esResponse.hits() != null) {
						elasticResponseDTO.setTotal(esResponse.hits().total().value());
						elasticResponseDTO.setPageNum(elasticRequest.getPage());
					}
					JsonObject convertedResponse = elasticResponseFormatter.convertSearchResponseToJsonObj(esResponse, elasticRequest);
					if (convertedResponse != null && convertedResponse.has("hits")) {
						elasticResponseDTO.setResponse(convertedResponse.get("hits").toString());
					}
					if (convertedResponse != null && convertedResponse.has("aggregations")) {
						elasticResponseDTO.setAggregation(convertedResponse.get("aggregations").toString());
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return elasticResponseDTO;
	}

	public SearchRequest buildQueryFromRequest(ElasticRequestDTO elasticRequest) {
		ElasticQueryCriteraDTO criterialList = elasticRequest.getCriteria();
		SearchRequest.Builder searchRequest = new SearchRequest.Builder();
		try {
			if (criterialList != null) {
				if (criterialList.getQueryName() != null) {

					BoolQuery.Builder boolQueryBuilder = new BoolQuery.Builder();

					List<ElasticRequestAttributeDTO> elasticQueryTerms = criterialList.getParamaList();
					if (elasticQueryTerms == null) {
						elasticQueryTerms = new ArrayList<>();
					}
					if (criterialList.getFilter() != null && criterialList.getFilter().length > 0) {
						ElasticRequestAttributeDTO elasticRequestAttributeDTO = null;
						Filter[] queryFilters = criterialList.getFilter();
						for (Filter filter : queryFilters) {
							if (filter.getAttributeName() != null && filter.getAttributeValue() != null) {
								if (!filter.getAttributeName().contains("start") && !filter.getAttributeName().contains("page") && !filter.getAttributeName().contains("limit")) {
									elasticRequestAttributeDTO = new ElasticRequestAttributeDTO();
									if (filter.getAttributeValue() instanceof String) {
										elasticRequestAttributeDTO.setName(filter.getAttributeName());
										elasticRequestAttributeDTO.setValue("*" + filter.getAttributeValue().toString() + "*");
									} else {
										elasticRequestAttributeDTO.setName(filter.getAttributeName());
										Gson gson = new Gson();
										JsonParser parser = new JsonParser();
										JsonElement jsonElement = parser.parse(gson.toJson(filter.getAttributeValue()));
										if (jsonElement.isJsonArray()) {
											elasticRequestAttributeDTO.setOperation(ElasticOperations.MULTIPLE);
										}
										String queryValue = parser.parse(gson.toJson(filter.getAttributeValue())).toString();
										elasticRequestAttributeDTO.setValue(queryValue);
										elasticRequestAttributeDTO.setOperator(filter.getOperator());
									}
								}
							}
							elasticQueryTerms.add(elasticRequestAttributeDTO);
						}
					}
					for (ElasticRequestAttributeDTO elasticRequestAttribute : elasticQueryTerms) {
						updateQueryBuilderFromOperation(searchRequest.build(), boolQueryBuilder, elasticRequestAttribute);
					}

					searchRequest.query(boolQueryBuilder.build()._toQuery());
				}
			}

		} catch (Exception e) {
			LOGGER.error("Exception in ElasticQueryBuilder prepareQueryTemplate method", e);
		}

		return searchRequest.build();
	}


	private void updateQueryBuilderFromOperation(SearchRequest searchReqBuilder, BoolQuery.Builder boolQueryBuilder, ElasticRequestAttributeDTO elasticRequestAttribute) {
		if (elasticRequestAttribute.getOperation() != null && elasticRequestAttribute.getName() != null && !elasticRequestAttribute.getName().isEmpty()) {
			switch (elasticRequestAttribute.getOperation()) {
				case AVG:
					Aggregation avgAggregation = Aggregation.of(a -> a.avg(agg -> agg.field(elasticRequestAttribute.getName())));
					searchReqBuilder.aggregations().put(elasticRequestAttribute.getName(), avgAggregation);
					break;
				case GROUP:
					Aggregation groupAggregation = Aggregation.of(a -> a.terms(terms -> terms.field(elasticRequestAttribute.getName())));
					searchReqBuilder.aggregations().put(elasticRequestAttribute.getName(), groupAggregation);
					break;
				case COUNT:
					Aggregation countAggregation = Aggregation.of(a -> a.valueCount(vc -> vc.field(elasticRequestAttribute.getName())));
					searchReqBuilder.aggregations().put(elasticRequestAttribute.getName(), countAggregation);
					break;
				case MAX:
					Aggregation maxAggregation = Aggregation.of(a -> a.max(max -> max.field(elasticRequestAttribute.getName())));
					searchReqBuilder.aggregations().put(elasticRequestAttribute.getName(), maxAggregation);
					break;
				case MIN:
					Aggregation minAggregation = Aggregation.of(a -> a.min(min -> min.field(elasticRequestAttribute.getName())));
					searchReqBuilder.aggregations().put(elasticRequestAttribute.getName(), minAggregation);
					break;
				case RANGE:
					Aggregation rangeAggregation = Aggregation.of(a -> a.range(range -> range.field(elasticRequestAttribute.getName())));
					searchReqBuilder.aggregations().put(elasticRequestAttribute.getName(), rangeAggregation);
					break;
				case LIKE:
					boolQueryBuilder.filter(QueryBuilders.queryString().query((elasticRequestAttribute.getName() + ":(" + elasticRequestAttribute.getValue() + ")")).analyzeWildcard(true).build()._toQuery());
					break;
				case FUZZY:
					FuzzyQuery fuzzyQuery = FuzzyQuery.of(f -> f
							.field(elasticRequestAttribute.getName())
							.value(elasticRequestAttribute.getValue())
							.fuzziness("AUTO")
							.prefixLength(0)
							.maxExpansions(50)
					);
					boolQueryBuilder.filter(fuzzyQuery._toQuery());
					break;


				case NOT:
					boolQueryBuilder.mustNot(m -> m
							.match(match -> match
									.field(elasticRequestAttribute.getName())
									.query(elasticRequestAttribute.getValue())
							)
					);
					break;

				case ASC:
					searchReqBuilder.sort().add(SortOptions.of(s -> s
							.field(f -> f
									.field(elasticRequestAttribute.getName())
									.order(SortOrder.Asc)
							)
					));
					break;

				case DESC:
					searchReqBuilder.sort().add(SortOptions.of(s -> s
							.field(f -> f
									.field(elasticRequestAttribute.getName())
									.order(SortOrder.Desc)
							)
					));
					break;

                case MULTIPLE:
					boolQueryBuilder.filter(f -> f
							.term(t -> t
									.field(elasticRequestAttribute.getName())
									.value(elasticRequestAttribute.getValue())
							)
					);
					break;

				case MUST:
					boolQueryBuilder.must(m -> m
							.match(match -> match
									.field(elasticRequestAttribute.getName())
									.query(elasticRequestAttribute.getValue())
							)
					);
					break;

				case PREFIX:
					boolQueryBuilder.filter(f -> f
							.matchPhrasePrefix(mpp -> mpp
									.field(elasticRequestAttribute.getName())
									.query(elasticRequestAttribute.getValue().toString())
									.maxExpansions(5)
							)
					);
					break;
				default:
					break;
			}
		} else {
			if ((elasticRequestAttribute.getMaxValue() != null && elasticRequestAttribute.getMinValue() != null) || elasticRequestAttribute.getValue() != null) {
				updateQueryFromDataTypeBuilder(boolQueryBuilder, elasticRequestAttribute);
			}
		}
	}
	public void updateQueryFromDataTypeBuilder(BoolQuery.Builder boolQueryBuilder, ElasticRequestAttributeDTO elasticRequestAttribute) {
		if (elasticRequestAttribute.getType() != null && elasticRequestAttribute.getName() != null && !elasticRequestAttribute.getName().isEmpty()) {
			switch (elasticRequestAttribute.getType()) {
				case DATE:
					String defaultDateFormat;
					if (elasticRequestAttribute.getMinValue() != null && elasticRequestAttribute.getMaxValue() != null) {
						if (elasticRequestAttribute.getReqFormat() != null) {
							defaultDateFormat = elasticRequestAttribute.getReqFormat();
						} else {
                            defaultDateFormat = "dd-MM-yyyy";
                        }
                        boolQueryBuilder.filter(f -> f.range(r -> r
								.field(elasticRequestAttribute.getName())
								.format(defaultDateFormat)
								.gte(JsonData.of(elasticRequestAttribute.getMinValue() + "||/d"))
								.lte(JsonData.of(elasticRequestAttribute.getMaxValue() + "||/d"))
						));
					} else {
                        defaultDateFormat = "dd-MM-yyyy";
                        if (elasticRequestAttribute.getValue() != null) {
							boolQueryBuilder.filter(f -> f.range(r -> r
									.field(elasticRequestAttribute.getName())
									.format(defaultDateFormat)
									.gte(JsonData.of(elasticRequestAttribute.getValue() + "||/d"))
									.lte(JsonData.of(elasticRequestAttribute.getMaxValue() + "||/d"))
							));
                        } else {
                            boolQueryBuilder.filter(f -> f.range(r -> r
                                    .field(elasticRequestAttribute.getName())
									.gte(JsonData.of("now-1M/M"))
									.lte(JsonData.of("now/d"))
                            ));
                        }
                    }
					break;
				case NUMBER:
					if (elasticRequestAttribute.getValue() != null) {
						boolQueryBuilder.filter(f -> f.queryString(qs -> qs
								.query(elasticRequestAttribute.getName() + ":(" + elasticRequestAttribute.getValue() + ")")
						));
					}
					break;
				case STRING:
					boolQueryBuilder.filter(f -> f.match(m -> m
							.field(elasticRequestAttribute.getName())
							.query(FieldValue.of(elasticRequestAttribute.getValue() + ""))
					));
					break;
				case NESTED:
					if (elasticRequestAttribute.getValue() != null) {
						boolQueryBuilder.must(m -> m.nested(n -> n
								.path(elasticRequestAttribute.getName().substring(0, elasticRequestAttribute.getName().lastIndexOf(".")))
								.query(nq -> nq.bool(innerBool -> innerBool
										.must(mu -> mu.match(mm -> mm
												.field(elasticRequestAttribute.getName())
												.query(elasticRequestAttribute.getValue())
										))
								))
								.scoreMode(ChildScoreMode.Avg)
						));
					}
					break;
				default:
					boolQueryBuilder.must(m -> m.match(mm -> mm
							.field(elasticRequestAttribute.getName())
							.query(elasticRequestAttribute.getValue())
					));
					break;
			}
		} else {
			if (elasticRequestAttribute.getName() != null && !elasticRequestAttribute.getName().isEmpty() && elasticRequestAttribute.getValue() != null) {
				if (elasticRequestAttribute.getOperator() != null) {
					boolQueryBuilder.filter(f -> f.queryString(qs -> qs
							.query(elasticRequestAttribute.getName() + ": (" + elasticRequestAttribute.getOperator() + elasticRequestAttribute.getValue() + ")")
							.analyzeWildcard(true)
					));
				} else {
					boolQueryBuilder.filter(f -> f.queryString(qs -> qs
							.query(elasticRequestAttribute.getName() + ": " + elasticRequestAttribute.getValue() + "")
							.analyzeWildcard(true)
					));
				}
			}
		}
	}
}
