package org.sunbird.learner.actors.tenantpreference;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.sunbird.actor.core.BaseActor;
import org.sunbird.actor.router.ActorConfig;
import org.sunbird.cassandra.CassandraOperation;
import org.sunbird.common.exception.ProjectCommonException;
import org.sunbird.common.models.response.Response;
import org.sunbird.common.models.util.ActorOperations;
import org.sunbird.common.models.util.JsonKey;
import org.sunbird.common.models.util.LoggerEnum;
import org.sunbird.common.models.util.ProjectLogger;
import org.sunbird.common.request.Request;
import org.sunbird.common.responsecode.ResponseCode;
import org.sunbird.helper.ServiceFactory;
import org.sunbird.learner.util.Util;

/** Class for Tenant preferences . Created by arvind on 27/10/17. */
@ActorConfig(
  tasks = {
    "createTanentPreference",
    "updateTenantPreference",
    "getTenantPreference",
  },
  asyncTasks = {}
)
public class TenantPreferenceManagementActor extends BaseActor {

  private CassandraOperation cassandraOperation = ServiceFactory.getInstance();
  private Util.DbInfo tenantPreferenceDbInfo = Util.dbInfoMap.get(JsonKey.TENANT_PREFERENCE_V2);
  private static final String DEFAULT_WILDCARD_ORG_ID = "*";
  private ObjectMapper mapper = new ObjectMapper();

  @Override
  public void onReceive(Request request) throws Throwable {
    if (request
        .getOperation()
        .equalsIgnoreCase(ActorOperations.CREATE_TENANT_PREFERENCE.getValue())) {
      createTenantPreference(request);
    } else if (request
        .getOperation()
        .equalsIgnoreCase(ActorOperations.UPDATE_TENANT_PREFERENCE.getValue())) {
      updateTenantPreference(request);
    } else if (request
        .getOperation()
        .equalsIgnoreCase(ActorOperations.GET_TENANT_PREFERENCE.getValue())) {
      getTenantPreference(request);
    } else {
      onReceiveUnsupportedOperation(request.getOperation());
    }
  }

  /**
   * Method to get Tenant preference of the given root org id .
   *
   * @param actorMessage
   */
  @SuppressWarnings("unchecked")
  private void getTenantPreference(Request actorMessage) {
    String orgId = (String) actorMessage.getRequest().get(JsonKey.ROOT_ORG_ID);
    ProjectLogger.log(
        "TenantPreferenceManagementActor-getTenantPreference called for org: " + orgId);
    validateRequest(actorMessage, false);
    List<String> keys = (List<String>) actorMessage.getRequest().get(JsonKey.KEYS);

    Map<String, Map<String, Object>> orgPrefMap = getPreferenceMap(getPreferencesFromDB(orgId));
    Map<String, Map<String, Object>> defaultPrefMap =
        getPreferenceMap(getPreferencesFromDB(DEFAULT_WILDCARD_ORG_ID));

    Response finalResponse = new Response();
    List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
    if (null != keys && !keys.isEmpty()) {
      for (String key : keys) {
        if (null != orgPrefMap.get(key)) list.add(orgPrefMap.get(key));
        else if (null != defaultPrefMap.get(key)) list.add(defaultPrefMap.get(key));
      }
    } else {
      if (!orgPrefMap.isEmpty()) list.addAll(orgPrefMap.values());
      else if (!defaultPrefMap.isEmpty()) list.addAll(defaultPrefMap.values());
    }
    finalResponse.getResult().put(JsonKey.TENANT_PREFERENCE, list);
    sender().tell(finalResponse, self());
  }

  /**
   * Method to update the Tenant preference on basis of id or (role and org id).
   *
   * @param actorMessage
   */
  @SuppressWarnings("unchecked")
  private void updateTenantPreference(Request actorMessage) {

    String orgId = (String) actorMessage.getRequest().get(JsonKey.ROOT_ORG_ID);
    ProjectLogger.log(
        "TenantPreferenceManagementActor-updateTenantPreference called for org: " + orgId);
    validateRequest(actorMessage, true);

    Response finalResponse = new Response();
    List<Map<String, Object>> responseList = new ArrayList<>();
    List<Map<String, Object>> reqList =
        (List<Map<String, Object>>) actorMessage.getRequest().get(JsonKey.TENANT_PREFERENCE);
    List<Map<String, Object>> preferencesList = getPreferencesFromDB(orgId);
    for (Map<String, Object> map : reqList) {
      Map<String, Object> preferenceObj = null;
      String key = (String) map.get(JsonKey.KEY);
      String data = (String) map.get(JsonKey.DATA);
      // skip update if either key or data is empty
      if (StringUtils.isNotBlank(key) && StringUtils.isNotBlank(data)) {
        boolean found = false;
        for (Map<String, Object> m : preferencesList) {
          // check if preference key exists for the org
          if (key.equals(m.get(JsonKey.KEY))) {
            preferenceObj = m;
            found = true;
            break;
          }
        }
        // if preference is not found
        if (!found)
          responseList.add(
              getResponseMap(
                  orgId,
                  key,
                  "Preference setting not found for key: " + key + " for the org: " + orgId));
        // if preference is found
        if (null != preferenceObj) {
          preferenceObj.put(JsonKey.KEY, key);
          preferenceObj.put(JsonKey.DATA, data);
          cassandraOperation.updateRecord(
              tenantPreferenceDbInfo.getKeySpace(),
              tenantPreferenceDbInfo.getTableName(),
              preferenceObj,
              null);
          responseList.add(getResponseMap(orgId, key, null));
        }
      }
    }
    finalResponse.getResult().put(JsonKey.RESPONSE, responseList);
    sender().tell(finalResponse, self());
  }

  /**
   * Method to create tenant preference for an org , if already exists it will not create new one.
   *
   * @param actorMessage
   */
  private void createTenantPreference(Request actorMessage) {
    Map<String, Object> req = actorMessage.getRequest();
    String orgId = (String) req.get(JsonKey.ORG_ID);
    ProjectLogger.log(
        "TenantPreferenceManagementActor-createTenantPreference called for org: " + orgId);
    List<Map<String, Object>> preferencesList = getPreferencesFromDB(orgId);
    Response finalResponse = new Response();
    String key = (String) req.get(JsonKey.KEY);
    // check whether already tenant preference exists for the given org id
    if (CollectionUtils.isNotEmpty(preferencesList)) {
      for (Map<String, Object> preference : preferencesList) {
        ProjectLogger.log(
            "TenantPreferenceManagementActor-createTenantPreference key "
                + key
                + "already exists in the org "
                + orgId);
        if (key.equalsIgnoreCase((String) preference.get(JsonKey.KEY))) {
          throw new ProjectCommonException(
              ResponseCode.preferenceAlreadyExists.getErrorCode(),
              MessageFormat.format(
                  ResponseCode.preferenceAlreadyExists.getErrorMessage(), key, orgId),
              ResponseCode.CLIENT_ERROR.getResponseCode());
        }
      }
    }
    Map<String, Object> dbMap = new HashMap<>();
    try {
      // create the preference if not already exists
      dbMap.put(JsonKey.ORG_ID, orgId);
      dbMap.put(JsonKey.KEY, key);
      dbMap.put(JsonKey.DATA, serialize(req.get(JsonKey.DATA)));
      dbMap.put(JsonKey.CREATED_BY, actorMessage.getContext().get(JsonKey.REQUESTED_BY));
      dbMap.put(JsonKey.CREATED_ON, new Timestamp(Calendar.getInstance().getTimeInMillis()));
    } catch (Exception e) {
      ProjectLogger.log("exception while adding preferences " + e.getMessage(), LoggerEnum.DEBUG);
    }
    Response response =
        cassandraOperation.insertRecord(
            tenantPreferenceDbInfo.getKeySpace(),
            tenantPreferenceDbInfo.getTableName(),
            dbMap,
            null);
    finalResponse.getResult().put(JsonKey.ORG_ID, orgId);
    finalResponse.getResult().put(JsonKey.KEY, key);
    finalResponse.getResult().put(JsonKey.RESPONSE, JsonKey.SUCCESS);
    sender().tell(finalResponse, self());
  }

  private String serialize(Object obj) throws Exception {
    return mapper.writeValueAsString(obj);
  }

  private Map<String, Object> getResponseMap(String orgId, String key, String error) {
    Map<String, Object> responseMap = new HashMap<>();
    responseMap.put(JsonKey.ROOT_ORG_ID, orgId);
    responseMap.put(JsonKey.KEY, key);
    if (StringUtils.isNotBlank(error)) {
      responseMap.put(JsonKey.STATUS, JsonKey.FAILED);
      responseMap.put(
          JsonKey.ERROR, "Preference setting not found for key: " + key + " for the org: " + orgId);
    } else responseMap.put(JsonKey.STATUS, JsonKey.SUCCESS);
    return responseMap;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> getPreferencesFromDB(String orgId) {
    Response tenantPreferences =
        cassandraOperation.getRecordsByProperty(
            tenantPreferenceDbInfo.getKeySpace(),
            tenantPreferenceDbInfo.getTableName(),
            JsonKey.ORG_ID,
            orgId,
            null);
    List<Map<String, Object>> preferencesList =
        (List<Map<String, Object>>) tenantPreferences.get(JsonKey.RESPONSE);
    return preferencesList;
  }

  @SuppressWarnings("unchecked")
  private void validateRequest(Request actorMessage, boolean update) {

    String orgId = (String) actorMessage.getRequest().get(JsonKey.ROOT_ORG_ID);
    if (StringUtils.isBlank(orgId)) {
      // throw invalid ord id ,org id should not be null or empty .
      throw new ProjectCommonException(
          ResponseCode.invalidOrgId.getErrorCode(),
          ResponseCode.invalidOrgId.getErrorMessage(),
          ResponseCode.CLIENT_ERROR.getResponseCode());
    }

    // skipping this check for now to allow storing of channel based preferences
    /*
     * // check if the org id is either a valid org in the system or is the default wild card
     * org (*) if (!StringUtils.equalsIgnoreCase(DEFAULT_WILDCARD_ORG_ID, orgId)) { // check
     * whether org exist or not Response result =
     * cassandraOperation.getRecordById(orgDbInfo.getKeySpace(), orgDbInfo.getTableName(),
     * orgId); List<Map<String, Object>> orglist = (List<Map<String, Object>>)
     * result.get(JsonKey.RESPONSE); if (null == orglist || orglist.isEmpty()) throw new
     * ProjectCommonException(ResponseCode.invalidOrgId.getErrorCode(),
     * ResponseCode.invalidOrgId.getErrorMessage(),
     * ResponseCode.CLIENT_ERROR.getResponseCode()); }
     */

    if (update) {
      List<Map<String, Object>> req =
          (List<Map<String, Object>>) actorMessage.getRequest().get(JsonKey.TENANT_PREFERENCE);
      // no need to do anything throw exception invalid request data as list is empty
      if (null == req || req.isEmpty())
        throw new ProjectCommonException(
            ResponseCode.invalidRequestData.getErrorCode(),
            ResponseCode.invalidRequestData.getErrorMessage(),
            ResponseCode.CLIENT_ERROR.getResponseCode());
    }
  }

  private Map<String, Map<String, Object>> getPreferenceMap(
      List<Map<String, Object>> preferencesList) {
    Map<String, Map<String, Object>> keyMap = new HashMap<String, Map<String, Object>>();
    if (null != preferencesList && !preferencesList.isEmpty()) {
      for (Map<String, Object> m : preferencesList) {
        String key = (String) m.get(JsonKey.KEY);
        keyMap.put(key, m);
      }
    }
    return keyMap;
  }
}
