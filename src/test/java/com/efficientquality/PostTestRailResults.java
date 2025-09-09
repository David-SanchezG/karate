package com.efficientquality;

import org.apache.commons.codec.binary.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class PostTestRailResults {

    private static final String TESTRAIL_URL = "https://YOUR_TESTRAIL_DOMAIN.testrail.net/";
    private static final String USERNAME = "YOUR_EMAIL";
    private static final String PASSWORD = "YOUR_TESTRAIL_API_KEY";
    private static final int PROJECT_ID = YOUR_PROEJCT_ID;
    private static final int SECTION_ID = YOUR_STARTING_SECTION_ID;
    private static final String AUTH = "Basic " + Base64.encodeBase64String((USERNAME + ":" + PASSWORD).getBytes());
    private static final int PASSED_STATUS = 1;
    private static final int FAILED_STATUS = 5;
    private static final int SUITE_ID = YOUR_SUITE_ID;

    private static final HttpClient client = HttpClient.newHttpClient();

    public static void postResults(String reportsDir, String ticket) throws Exception {
        List<JSONObject> results = getKarateResults(reportsDir);

        if (results.size() > 0) {
            List<Integer> caseIds = results.stream()
                    .map(result -> result.getInt("case_id"))
                    .collect(Collectors.toList());

            int testPlanId = createTestPlan(ticket, "New test plan for " + ticket, caseIds, AUTH);
            addTestRunResult(testPlanId, results, AUTH);
        }
    }

    public static int createTestPlan(String ticket, String testPlanName, List<Integer> caseIds, String auth)
            throws Exception {
        String url = TESTRAIL_URL + "index.php?/api/v2/add_plan/" + PROJECT_ID;

        JSONObject data = new JSONObject();
        data.put("name", testPlanName);

        JSONArray entries = new JSONArray();
        JSONObject entry = new JSONObject();
        entry.put("suite_id", SUITE_ID);
        entry.put("name", "New test run for " + ticket);
        entry.put("include_all", false);

        JSONArray caseIdArray = new JSONArray();
        caseIds.forEach(caseIdArray::put);

        entry.put("case_ids", caseIdArray);
        entry.put("refs", ticket);
        entries.put(entry);

        data.put("entries", entries);

        String requestBody = data.toString();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", auth)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 300) {
            JSONObject errorData = new JSONObject(response.body());
            throw new Exception("Error creating test plan: " + response.statusCode() + " - " + errorData.toString());
        }

        JSONObject responseData = new JSONObject(response.body());
        int testRunId = responseData.getJSONArray("entries").getJSONObject(0).getJSONArray("runs").getJSONObject(0)
                .getInt("id");
        int testPlanId = responseData.getInt("id");

        url = TESTRAIL_URL + "index.php?/api/v2/update_plan/" + testPlanId;

        data = new JSONObject();
        data.put("refs", ticket);
        requestBody = data.toString();

        request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", auth)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        response = client.send(request, HttpResponse.BodyHandlers.ofString());
               
        return testRunId;
    }

    public static void addTestRunResult(int testPlanId, List<JSONObject> results, String auth) throws Exception {
        String url = TESTRAIL_URL + "index.php?/api/v2/add_results_for_cases/" + testPlanId;

        JSONObject data = new JSONObject();
        JSONArray resultsArray = new JSONArray();
        results.forEach(resultsArray::put);
        data.put("results", resultsArray);

        String requestBody = data.toString();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", auth)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 300) {
            JSONObject errorData = new JSONObject(response.body());
            throw new Exception("Error adding test results: " + response.statusCode() + " - " + errorData.toString());
        }
    }

    public static int findCaseIdByTitle(List<JSONObject> cases, String caseTitle) {
        return cases.stream()
                .filter(c -> c.getString("title").equals(caseTitle))
                .findFirst()
                .map(c -> c.getInt("id"))
                .orElse(-1);
    }

    public static List<JSONObject> getKarateResults(String reportsDir) throws IOException {
        List<JSONObject> resultsToPost = new ArrayList<>();
        File reportDirFile = new File(reportsDir);
        File[] jsonFiles = reportDirFile.listFiles(pathName -> pathName.getName().endsWith(".json"));
        if (jsonFiles == null || jsonFiles.length == 0) {
            System.out.println("No Cucumber JSON reports found in directory: {}" + reportsDir);
            return null;
        }
        for (File jsonFile : jsonFiles) {
            String resultsJsonString = new String(
                    Files.readAllBytes(Paths.get(jsonFile.getAbsolutePath())));
            JSONArray results = new JSONArray(resultsJsonString);

            List<JSONObject> cases = getCasesInSection(PROJECT_ID, SECTION_ID, AUTH);

            for (int i = 0; i < results.length(); i++) {
                JSONObject feature = results.getJSONObject(i);
                String featureTitle = feature.getString("description");
                JSONArray scenarios = feature.getJSONArray("elements");

                for (int j = 0; j < scenarios.length(); j++) {
                    JSONObject scenario = scenarios.getJSONObject(j);
                    String scenarioComment = "";
                    int scenarioStatus = isScenarioFailed(scenario) ? FAILED_STATUS : PASSED_STATUS;

                    if (scenarioStatus == FAILED_STATUS) {
                        scenarioComment = findErrorMessage(scenario);
                    }

                    if (scenario.getString("keyword").equals("Background")) {
                        j++;
                        if (scenarioStatus != FAILED_STATUS) {
                            JSONObject nextScenario = scenarios.getJSONObject(j);
                            scenarioStatus = isScenarioFailed(nextScenario) ? FAILED_STATUS : PASSED_STATUS;
                            if (scenarioStatus == FAILED_STATUS) {
                                scenarioComment = findErrorMessage(nextScenario);
                            }
                        }
                    }

                    String exampleLine = scenario.getString("keyword").equals("Scenario Outline")
                            ? "" + scenario.getInt("line")
                            : "";
                    Integer caseId = findCaseIdByTitle(cases,
                            featureTitle + " - " + scenario.getString("name") + " - " + exampleLine);

                    if (caseId != -1) {
                        JSONObject result = new JSONObject();
                        result.put("case_id", caseId);
                        result.put("status_id", scenarioStatus);
                        result.put("comment", scenarioComment);
                        resultsToPost.add(result);
                    }
                }
            }
        }

        return resultsToPost;
    }

    private static boolean isScenarioFailed(JSONObject scenario) {
        JSONArray steps = scenario.getJSONArray("steps");
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.getJSONObject(i);
            if (step.getJSONObject("result").has("status")
                    && step.getJSONObject("result").getString("status").equals("failed")) {
                return true;
            }
        }
        return false;
    }

    private static String findErrorMessage(JSONObject scenario) {
        JSONArray steps = scenario.getJSONArray("steps");
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.getJSONObject(i);
            JSONObject result = step.getJSONObject("result");
            if (result.has("status") && result.getString("status").equals("failed") && result.has("error_message")) {
                return result.getString("error_message");
            }
        }
        return "";
    }

    public static List<JSONObject> getCasesInSection(int projectId, int sectionId, String auth) {
        List<JSONObject> allCases = new ArrayList<>();
        int offset = 0;
        int limit = 250;

        try {
            while (true) {
                String url = TESTRAIL_URL + "index.php?/api/v2/get_cases/" + projectId + "&suite_id=" + SUITE_ID
                        + "&section_id=" + sectionId + "&offset=" + offset + "&limit=" + limit;

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .header("Authorization", auth)
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() >= 300) {
                    JSONObject errorData = new JSONObject(response.body());
                    throw new Exception("Error getting cases: " + response.statusCode() + " - " + errorData.toString());
                }

                JSONObject responseBody = new JSONObject(response.body());

                JSONArray cases = new JSONArray(responseBody.getJSONArray("cases"));
                if (cases.length() == 0) {
                    break;
                }

                for (int i = 0; i < cases.length(); i++) {
                    allCases.add(cases.getJSONObject(i));
                }

                offset += limit;
            }
        } catch (Exception e) {
            System.err.println("Exception in getCasesInSection: " + e.getMessage());
        }

        return allCases;
    }
}
