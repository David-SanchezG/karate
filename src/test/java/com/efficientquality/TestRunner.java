package com.efficientquality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intuit.karate.Results;
import com.intuit.karate.Runner;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.thymeleaf.postprocessor.PostProcessor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;

import net.masterthought.cucumber.Configuration;
import net.masterthought.cucumber.ReportBuilder;
import net.masterthought.cucumber.json.support.Status;

import org.apache.commons.io.FileUtils;

class TestRunner {

    @Test
    void testParallel() {
        long startTime = System.currentTimeMillis();
        Results results = Runner.path("classpath:com/efficientquality")
                .tags(System.getProperty("tags").split(" "))
                .outputCucumberJson(true)
                .outputJunitXml(true)
                .parallel(5);
        reportFailedBackgroundIfExist(results.getReportDir());
        long endTime = System.currentTimeMillis();
        Duration duration = Duration.ofMillis(endTime - startTime);
        long minutes = duration.toMinutes();
        long seconds = duration.toSecondsPart();
        generateReport(results.getReportDir(), String.format("%02d:%02d", minutes, seconds));
        if(System.getProperty("jiraTicket") != null && !System.getProperty("jiraTicket").isEmpty()){
            try {
                PostTestRailResults.postResults(results.getReportDir(), System.getProperty("jiraTicket"));
            } catch (Exception e) {
                System.out.println("Error synchronizing test results: " + e.getMessage()); 
            }
        }
    }

    public static void generateReport(String karateOutputPath, String totalTime) {
        Collection<File> jsonFiles = FileUtils.listFiles(new File(karateOutputPath), new String[] { "json" }, true);
        List<String> jsonPaths = new ArrayList<>(jsonFiles.size());
        jsonFiles.forEach(file -> jsonPaths.add(file.getAbsolutePath()));
        Configuration config = new Configuration(new File("target"), "Gaming Karate Test");
        config.setNotFailingStatuses(Collections.singleton(Status.SKIPPED));
        config.addClassifications("Total time", totalTime);
        config.addClassifications("Tags", System.getProperty("tags"));
        ReportBuilder reportBuilder = new ReportBuilder(jsonPaths, config);
        reportBuilder.generateReports();
        System.out.println("Cucumber report: " + System.getProperty("user.dir")
                + "/target/cucumber-html-reports/overview-features.html");
    }

    private static void reportFailedBackgroundIfExist(String reportsDir) {
        ObjectMapper objectMapper = new ObjectMapper();
        File reportDirFile = new File(reportsDir);
        File[] jsonFiles = reportDirFile.listFiles(pathName -> pathName.getName().endsWith(".json"));
        if (jsonFiles == null || jsonFiles.length == 0) {
            System.out.println("No Cucumber JSON reports found in directory: {}" + reportsDir);
            return;
        }
        for (File jsonFile : jsonFiles) {
            try {
                String jsonContent = new String(Files.readAllBytes(Paths.get(jsonFile.getAbsolutePath())));
                List<Map> features = objectMapper.readValue(jsonContent, List.class);
                for (Map feature : features) {
                    List<Map> elements = (List<Map>) feature.get("elements");
                    Map failedBackground = null;
                    for (Map element : elements) {
                        String elementType = (String) element.get("type");

                        if ("background".equals(elementType)) {
                            failedBackground = null;
                            List<Map> steps = (List<Map>) element.get("steps");
                            if (steps != null) { 
                                for (Map step : steps) {
                                    Map result = (Map) step.get("result");
                                    String status = (String) result.get("status");
                                    if ("failed".equals(status)) {
                                        failedBackground = element;
                                        break;
                                    }
                                }
                            }
                        } else if ("scenario".equals(elementType) && failedBackground != null){
                            int backgroundIndex = elements.indexOf(failedBackground);
                            int scenarioIndex = elements.indexOf(element);
                            if (scenarioIndex > backgroundIndex) {
                                List<Map> scenarioSteps = (List<Map>) element.get("steps");
                                if (scenarioSteps != null) {
                                    Map result = (Map) scenarioSteps.get(0).get("result");
                                    result.put("status", "failed");
                                    result.put("error_message", "Scenario failed in Background phase");
                                }
                            }
                        }
                    }
                }
                String modifiedJson = objectMapper.writeValueAsString(features);
                Files.write(Paths.get(jsonFile.getAbsolutePath()), modifiedJson.getBytes());
            } catch (IOException e) {
                System.out.println("Error processing Cucumber JSON file: {}" + jsonFile.getName() + " " + e);
            }
        }
    }

}
