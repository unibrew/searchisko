/*
 * JBoss, Home of Professional Open Source
 * Copyright 2012 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 */
package org.searchisko.api.rest;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriInfo;

import org.elasticsearch.action.search.MultiSearchRequestBuilder;
import org.elasticsearch.action.search.MultiSearchResponse;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.Client;
import org.elasticsearch.index.query.QueryBuilders;
import org.searchisko.api.ContentObjectFields;
import org.searchisko.api.model.QuerySettings;
import org.searchisko.api.rest.exception.BadFieldException;
import org.searchisko.api.rest.exception.RequiredFieldException;
import org.searchisko.api.service.SearchClientService;
import org.searchisko.api.util.QuerySettingsParser;
import org.searchisko.api.util.SearchUtils;

/**
 * Suggestions REST API.
 * 
 * @author Lukas Vlcek
 */
@RequestScoped
@Path("/suggestions")
@Produces(MediaType.APPLICATION_JSON)
public class SuggestionsRestService extends RestServiceBase {

	public static final String SEARCH_INDEX_NAME = "data_project_info";

	// TODO: in order to make this generic this value needs to be either injected to provided by a service
	public static final String SEARCH_INDEX_TYPE = "jbossorg_project_info";

	public static final Integer DEFAULT_SIZE = 5;
	public static final Integer MAX_SIZE = 100;

	// we assume there is a multi field with .ngram and .edgengram analysis setup
	// see 'data_project_info.json' and 'jbossorg_project_info.json' files
	private static final String FIELD_PROJECT = ContentObjectFields.SYS_PROJECT;
	private static final String FIELD_PROJECT_NAME = ContentObjectFields.SYS_PROJECT_NAME;
	private static final String FIELD_PROJECT_NAME_NGRAM = FIELD_PROJECT_NAME + ".ngram";
	private static final String FIELD_PROJECT_NAME_EDGENGRAM = FIELD_PROJECT_NAME + ".edgengram";

	@Inject
	protected Logger log;

	@Inject
	protected SearchClientService searchClientService;

	@Inject
	protected QuerySettingsParser querySettingsParser;

	@GET
	@Path("/query_string")
	@Produces(MediaType.APPLICATION_JSON)
	public Object queryString(@Context UriInfo uriInfo) {

		MultivaluedMap<String, String> params = uriInfo.getQueryParameters();
		String query = SearchUtils.trimToNull(params.getFirst(QuerySettings.QUERY_KEY));
		if (query == null) {
			throw new IllegalArgumentException(QuerySettings.QUERY_KEY);
		}

		throw new RuntimeException("Method not implemented yet!");
	}

	@GET
	@Path("/project")
	@Produces(MediaType.APPLICATION_JSON)
	public Object project(@Context UriInfo uriInfo) {

		QuerySettings querySettings = null;

		try {

			if (uriInfo == null) {
				throw new BadFieldException("uriInfo");
			}
			MultivaluedMap<String, String> params = uriInfo.getQueryParameters();
			querySettings = querySettingsParser.parseUriParams(params);

			if (querySettings.getQuery() == null) {
				throw new RequiredFieldException(QuerySettings.QUERY_KEY);
			}

			Integer size = querySettings.getSize();
			if (size == null || size < 1) {
				size = DEFAULT_SIZE;
			} else if (size > MAX_SIZE) {
				size = MAX_SIZE;
			}

			final Client client = searchClientService.getClient();
			final MultiSearchRequestBuilder msrb = getProjectMultiSearchRequestBuilder(
					client.prepareMultiSearch(),
					getProjectSearchNGramRequestBuilder(
							client.prepareSearch().setIndices(SEARCH_INDEX_NAME).setTypes(SEARCH_INDEX_TYPE),
							querySettings.getQuery(), size, querySettings.getFields()),
					getProjectSearchFuzzyRequestBuilder(
							client.prepareSearch().setIndices(SEARCH_INDEX_NAME).setTypes(SEARCH_INDEX_TYPE),
							querySettings.getQuery(), size, querySettings.getFields()));

			String responseUuid = UUID.randomUUID().toString();

			final MultiSearchResponse searchResponse = msrb.execute().actionGet();

			return createResponse(searchResponse, responseUuid);
		} catch (IllegalArgumentException e) {
			throw new BadFieldException("unknown", e);
		}
	}

	/**
	 * 
	 * @param srb
	 * @param query
	 * @param size
	 * @param fields optional additional fields to return
	 * @return
	 */
	protected SearchRequestBuilder getProjectSearchNGramRequestBuilder(SearchRequestBuilder srb, String query, int size, List<String> fields) {
		srb
		.addFields(FIELD_PROJECT, FIELD_PROJECT_NAME)
		.setSize(size)
		.setSearchType(SearchType.QUERY_THEN_FETCH)
		.setQuery(
				QueryBuilders.queryString(query).analyzer("whitespace_lowercase").field(FIELD_PROJECT_NAME)
						.field(FIELD_PROJECT_NAME_EDGENGRAM).field(FIELD_PROJECT_NAME_NGRAM))
		.addHighlightedField(FIELD_PROJECT_NAME, 1, 0).addHighlightedField(FIELD_PROJECT_NAME_NGRAM, 1, 0)
		.addHighlightedField(FIELD_PROJECT_NAME_EDGENGRAM, 1, 0);

		if (fields != null && fields.size() > 0) {
			for (String field : fields)
				srb.addField(field);
		}

		return srb;
	}

	/**
	 * 
	 * @param srb
	 * @param query
	 * @param size
	 * @param fields optional additional fields to return
	 * @return
	 */
	protected SearchRequestBuilder getProjectSearchFuzzyRequestBuilder(SearchRequestBuilder srb, String query, int size, List<String> fields) {
		srb
			.addFields(FIELD_PROJECT, FIELD_PROJECT_NAME)
			.setSize(size)
			.setSearchType(SearchType.QUERY_THEN_FETCH)
			.setQuery(
					QueryBuilders.fuzzyLikeThisQuery(FIELD_PROJECT_NAME, FIELD_PROJECT_NAME_NGRAM)
							.analyzer("whitespace_lowercase").maxQueryTerms(10).likeText(query))
			.addHighlightedField(FIELD_PROJECT_NAME, 1, 0).addHighlightedField(FIELD_PROJECT_NAME_NGRAM, 1, 0)
			.addHighlightedField(FIELD_PROJECT_NAME_EDGENGRAM, 1, 0);

		if (fields != null && fields.size() > 0) {
			for (String field : fields)
				srb.addField(field);
		}

		return srb;
	}

	/**
	 * @param msrb
	 * @param srbNGram
	 * @param srbFuzzy
	 * @return
	 */
	protected MultiSearchRequestBuilder getProjectMultiSearchRequestBuilder(MultiSearchRequestBuilder msrb,
			SearchRequestBuilder srbNGram, SearchRequestBuilder srbFuzzy) {
		return msrb.add(srbNGram).add(srbFuzzy);
	}

}
