package org.terrakube.api.plugin.state;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.TextStringBuilder;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.terrakube.api.plugin.scheduler.ScheduleJobService;
import org.terrakube.api.plugin.state.model.apply.ApplyRunData;
import org.terrakube.api.plugin.state.model.apply.ApplyRunModel;
import org.terrakube.api.plugin.state.model.configuration.ConfigurationData;
import org.terrakube.api.plugin.state.model.configuration.ConfigurationModel;
import org.terrakube.api.plugin.state.model.entitlement.EntitlementData;
import org.terrakube.api.plugin.state.model.entitlement.EntitlementModel;
import org.terrakube.api.plugin.state.model.generic.Resource;
import org.terrakube.api.plugin.state.model.organization.OrganizationData;
import org.terrakube.api.plugin.state.model.organization.OrganizationModel;
import org.terrakube.api.plugin.state.model.plan.PlanRunData;
import org.terrakube.api.plugin.state.model.plan.PlanRunModel;
import org.terrakube.api.plugin.state.model.runs.*;
import org.terrakube.api.plugin.state.model.state.StateData;
import org.terrakube.api.plugin.state.model.state.StateModel;
import org.terrakube.api.plugin.state.model.workspace.WorkspaceData;
import org.terrakube.api.plugin.state.model.workspace.WorkspaceList;
import org.terrakube.api.plugin.state.model.workspace.WorkspaceModel;
import org.terrakube.api.plugin.state.model.workspace.state.consumers.StateConsumerList;
import org.terrakube.api.plugin.state.model.workspace.tags.TagDataList;
import org.terrakube.api.plugin.state.model.workspace.tags.TagModel;
import org.terrakube.api.plugin.storage.StorageTypeService;
import org.terrakube.api.repository.*;
import org.terrakube.api.rs.Organization;
import org.terrakube.api.rs.job.Job;
import org.terrakube.api.rs.job.JobStatus;
import org.terrakube.api.rs.job.step.Step;
import org.terrakube.api.rs.tag.Tag;
import org.terrakube.api.rs.template.Template;
import org.terrakube.api.rs.workspace.Workspace;
import org.terrakube.api.rs.workspace.content.Content;
import org.terrakube.api.rs.workspace.history.History;
import org.terrakube.api.rs.workspace.tag.WorkspaceTag;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class RemoteTfeService {

    private static final String GENERIC_STATE_PATH = "%s/tfstate/v1/organization/%s/workspace/%s/jobId/%s/step/%s/terraform.tfstate";
    private JobRepository jobRepository;
    private ContentRepository contentRepository;
    private OrganizationRepository organizationRepository;
    private WorkspaceRepository workspaceRepository;
    private HistoryRepository historyRepository;
    private TemplateRepository templateRepository;
    private ScheduleJobService scheduleJobService;
    private String hostname;
    private StorageTypeService storageTypeService;
    private StepRepository stepRepository;
    private RedisTemplate redisTemplate;

    private TagRepository tagRepository;

    private WorkspaceTagRepository workspaceTagRepository;

    public RemoteTfeService(JobRepository jobRepository,
                            ContentRepository contentRepository,
                            OrganizationRepository organizationRepository,
                            WorkspaceRepository workspaceRepository,
                            HistoryRepository historyRepository,
                            TemplateRepository templateRepository,
                            ScheduleJobService scheduleJobService,
                            @Value("${org.terrakube.hostname}") String hostname,
                            StorageTypeService storageTypeService,
                            StepRepository stepRepository,
                            RedisTemplate redisTemplate,
                            TagRepository tagRepository,
                            WorkspaceTagRepository workspaceTagRepository) {
        this.jobRepository = jobRepository;
        this.contentRepository = contentRepository;
        this.organizationRepository = organizationRepository;
        this.workspaceRepository = workspaceRepository;
        this.historyRepository = historyRepository;
        this.templateRepository = templateRepository;
        this.scheduleJobService = scheduleJobService;
        this.hostname = hostname;
        this.storageTypeService = storageTypeService;
        this.stepRepository = stepRepository;
        this.redisTemplate = redisTemplate;
        this.tagRepository = tagRepository;
        this.workspaceTagRepository = workspaceTagRepository;
    }

    EntitlementData getOrgEntitlementSet(String organizationName) {
        Organization organization = organizationRepository.getOrganizationByName(organizationName);
        if (organization != null) {
            EntitlementModel entitlementModel = new EntitlementModel();
            entitlementModel.setId("org-" + organizationName);
            Map<String, Object> entitlementAttributes = new HashMap<>();
            entitlementAttributes.put("operations", true);
            entitlementAttributes.put("private-module-registry", true);
            entitlementAttributes.put("sentinel", false);
            entitlementAttributes.put("run-tasks", false);
            entitlementAttributes.put("state-storage", true);
            entitlementAttributes.put("teams", false);
            entitlementAttributes.put("vcs-integrations", true);
            entitlementAttributes.put("usage-reporting", false);
            entitlementAttributes.put("user-limit", 5);
            entitlementAttributes.put("self-serve-billing", true);
            entitlementAttributes.put("audit-logging", false);
            entitlementAttributes.put("agents", false);
            entitlementAttributes.put("sso", false);
            entitlementModel.setAttributes(entitlementAttributes);
            entitlementModel.setType("entitlement-sets");
            EntitlementData entitlementData = new EntitlementData();
            entitlementData.setData(entitlementModel);

            log.info(entitlementData.toString());
            return entitlementData;
        } else {
            return null;
        }

    }

    OrganizationData getOrgInformation(String organizationName) {
        Organization organization = organizationRepository.getOrganizationByName(organizationName);
        if (organization != null) {
            OrganizationModel organizationModel = new OrganizationModel();
            organizationModel.setId(organizationName);
            organizationModel.setType("organizations");

            Map<String, Object> permissionMap = new HashMap<>();
            permissionMap.put("can-update", true);
            permissionMap.put("can-destroy", true);
            permissionMap.put("can-access-via-teams", false);
            permissionMap.put("can-create-module", false);
            permissionMap.put("can-create-team", false);
            permissionMap.put("can-create-workspace", true);
            permissionMap.put("can-manage-users", false);
            permissionMap.put("can-manage-subscription", false);
            permissionMap.put("can-manage-sso", false);
            permissionMap.put("can-update-oauth", false);
            permissionMap.put("can-update-sentinel", false);
            permissionMap.put("can-update-ssh-keys", false);
            permissionMap.put("can-update-api-token", false);
            permissionMap.put("can-traverse", false);
            permissionMap.put("can-start-trial", false);
            permissionMap.put("can-update-agent-pools", false);
            permissionMap.put("can-manage-tags", true);
            permissionMap.put("can-manage-public-modules", false);
            permissionMap.put("can-manage-public-providers", false);
            permissionMap.put("can-manage-run-tasks", true);
            permissionMap.put("can-read-run-tasks", true);
            permissionMap.put("can-create-provider", false);

            Map<String, Object> attributes = new HashMap<>();
            attributes.put("permissions", permissionMap);
            attributes.put("name", organizationName);
            organizationModel.setAttributes(attributes);

            OrganizationData organizationData = new OrganizationData();
            organizationData.setData(organizationModel);
            return organizationData;
        } else {
            return null;
        }
    }

    WorkspaceData getWorkspace(String organizationName, String workspaceName, Map<String, Object> otherAttributes) {
        Optional<Workspace> workspace = Optional
                .ofNullable(workspaceRepository.getByOrganizationNameAndName(organizationName, workspaceName));

        if (workspace.isPresent()) {
            log.info("Found Workspace Id: {} Terraform: {}", workspace.get().getId().toString(),
                    workspace.get().getTerraformVersion());
            WorkspaceData workspaceData = new WorkspaceData();

            WorkspaceModel workspaceModel = new WorkspaceModel();
            workspaceModel.setId(workspace.get().getId().toString());
            workspaceModel.setType("workspaces");
            Map<String, Object> attributes = new HashMap<>();
            attributes.put("name", workspaceName);
            attributes.put("terraform-version", workspace.get().getTerraformVersion());
            attributes.put("locked", workspace.get().isLocked());
            attributes.put("auto-apply", false);
            attributes.put("execution-mode", workspace.get().getExecutionMode());
            attributes.put("global-remote-state", true);

            Map<String, Boolean> defaultAttributes = new HashMap<>();
            defaultAttributes.put("can-create-state-versions", true);
            defaultAttributes.put("can-destroy", true);
            defaultAttributes.put("can-force-unlock", true);
            defaultAttributes.put("can-lock", true);
            defaultAttributes.put("can-manage-run-tasks", true);
            defaultAttributes.put("can-manage-tags", true);
            defaultAttributes.put("can-queue-apply", true);
            defaultAttributes.put("can-queue-destroy", true);
            defaultAttributes.put("can-queue-run", true);
            defaultAttributes.put("can-read-settings", true);
            defaultAttributes.put("can-read-state-versions", true);
            defaultAttributes.put("can-read-variable", true);
            defaultAttributes.put("can-unlock", true);
            defaultAttributes.put("can-update", true);
            defaultAttributes.put("can-update-variable", true);
            defaultAttributes.put("can-read-assessment-result", true);
            defaultAttributes.put("can-force-delete", true);
            //defaultAttributes.put("structured-run-output-enabled", true);

            attributes.put("permissions", defaultAttributes);

            otherAttributes.forEach((key, value) -> attributes.putIfAbsent(key, value));

            workspaceModel.setAttributes(attributes);
            workspaceData.setData(workspaceModel);

            return workspaceData;
        } else {
            return null;
        }

    }

    StateConsumerList getWorkspaceStateConsumers(String workspaceId) {
        Optional<Workspace> workspaceFound = Optional
                .ofNullable(workspaceRepository.getReferenceById(UUID.fromString(workspaceId)));

        StateConsumerList stateConsumerList = new StateConsumerList();
        stateConsumerList.setData(new ArrayList());

        workspaceFound.ifPresent(workspaceData -> {
            log.info("Workspace found {}, generating workspace list from organization", workspaceData.getName());
            workspaceData.getOrganization().getWorkspace().forEach(workspace -> {
                if (!workspace.getId().toString().equals(workspaceId)) {
                    log.info("Adding workspace {} as state consumers", workspace.getName());
                    stateConsumerList.getData().add(getWorkspace(workspace.getOrganization().getName(), workspace.getName(), new HashMap()).getData());
                }
            });
        });

        return stateConsumerList;

    }

    WorkspaceList listWorkspace(String organizationName, String searchTags) {
        WorkspaceList workspaceList = new WorkspaceList();
        workspaceList.setData(new ArrayList());

        List<String> listTags = Arrays.stream(searchTags.split(",")).toList();
        log.info("Searching workspaces with tags: {}", searchTags);
        for (Workspace workspace : organizationRepository.getOrganizationByName(organizationName).getWorkspace()) {
            List<WorkspaceTag> workspaceTagList = workspace.getWorkspaceTag();
            int matchingTags = 0;

            for (WorkspaceTag workspaceTag : workspaceTagList) {
                Tag tag = tagRepository.getReferenceById(UUID.fromString(workspaceTag.getTagId()));
                if (listTags.indexOf(tag.getName()) > -1) {
                    matchingTags++;
                }
            }
            log.info("Workspace {} Tags Count {} Searching Tag Quantity {} Matched {}", workspace.getName(), workspaceTagList.size(), listTags.size(), matchingTags);
            if (matchingTags == listTags.size()) {
                workspaceList.getData().add(getWorkspace(organizationName, workspace.getName(), new HashMap()).getData());
            }
        }
        return workspaceList;
    }

    boolean updateWorkspaceTags(String workspaceId, TagDataList tagDataList) {
        Workspace workspace = workspaceRepository.getReferenceById(UUID.fromString(workspaceId));
        tagDataList.getData().forEach(tagModel -> {
            Tag tag = searchOrCreateTagOrganization(workspace, tagModel.getAttributes().get("name"));
            log.info("Updating tag {} in Workspace {}", tagModel.getAttributes().get("name"), workspace.getName());
            if (workspaceTagRepository.getByWorkspaceAndTagId(workspace, tag.getId().toString()) == null) {
                log.info("Tag {} does not exist in workspace {}, adding new tag to workspace...", tagModel.getAttributes().get("name"), workspace.getName());
                WorkspaceTag newWorkspaceTag = new WorkspaceTag();
                newWorkspaceTag.setId(UUID.randomUUID());
                newWorkspaceTag.setTagId(tag.getId().toString());
                newWorkspaceTag.setWorkspace(workspace);
                workspaceTagRepository.save(newWorkspaceTag);
            } else {
                log.info("Tag {} exist in workspace {}, there is no need to update", tagModel.getAttributes().get("name"), workspace.getName());
            }
        });

        return true;
    }

    synchronized Tag searchOrCreateTagOrganization(Workspace workspace, String tagName) {
        log.info("Checking if Tag {} exists inside Organization {}", tagName, workspace.getOrganization().getName());
        Tag tag = tagRepository.getByOrganizationNameAndName(workspace.getOrganization().getName(), tagName);

        if (tag == null) {
            log.info("Creating new tag {} in Org {}", tagName, workspace.getOrganization().getName());
            tag = new Tag();
            try {
                tag.setId(UUID.randomUUID());
                tag.setName(tagName);
                tag.setOrganization(workspace.getOrganization());
                tag = tagRepository.save(tag);
            } catch (Exception exception) {
                log.error(exception.getMessage());
                tag = tagRepository.getByOrganizationNameAndName(workspace.getOrganization().getName(), tagName);
            }

        }
        return tag;
    }

    WorkspaceData updateWorkspace(String workspaceId, WorkspaceData workspaceData) {
        Optional<Workspace> workspace = Optional.ofNullable(workspaceRepository.getReferenceById(UUID.fromString(workspaceId)));

        log.info("Updating existing workspace {} in {}", workspace.get().getName(), workspace.get().getOrganization().getName());

        Workspace updatedWorkspace = workspace.get();
        updatedWorkspace.setTerraformVersion(workspaceData.getData().getAttributes().get("terraform-version").toString());

        workspaceRepository.save(updatedWorkspace);

        return getWorkspace(updatedWorkspace.getOrganization().getName(), updatedWorkspace.getName(), new HashMap<>());
    }

    WorkspaceData createWorkspace(String organizationName, WorkspaceData workspaceData) {
        Optional<Workspace> workspace = Optional.ofNullable(workspaceRepository.getByOrganizationNameAndName(
                organizationName, workspaceData.getData().getAttributes().get("name").toString()));

        if (workspace.isEmpty()) {
            log.info("Creating new workspace {} in {}", workspaceData.getData().getAttributes().get("name").toString(),
                    organizationName);

            Organization organization = organizationRepository.getOrganizationByName(organizationName);
            Workspace newWorkspace = new Workspace();
            newWorkspace.setId(UUID.randomUUID());
            newWorkspace.setName(workspaceData.getData().getAttributes().get("name").toString());
            String terraformVersion = "";
            if (workspaceData.getData().getAttributes().get("terraform-version") != null) {
                terraformVersion = workspaceData.getData().getAttributes().get("terraform-version").toString();
            } else {
                terraformVersion = "1.4.6";
                log.warn("Using default terraform version: {}", terraformVersion);
            }
            newWorkspace.setTerraformVersion(terraformVersion);
            newWorkspace.setSource("empty");
            newWorkspace.setBranch("remote-content");
            newWorkspace.setOrganization(organization);
            workspaceRepository.save(newWorkspace);
        }
        Map<String, Object> otherAttributes = new HashMap<>();
        otherAttributes.put("locked", false);
        return getWorkspace(organizationName, workspaceData.getData().getAttributes().get("name").toString(),
                otherAttributes);
    }

    WorkspaceData updateWorkspaceLock(String workspaceId, boolean locked) {
        log.info("Update Lock Workspace: {} to {}", workspaceId, locked);
        Workspace workspace = workspaceRepository.getReferenceById(UUID.fromString(workspaceId));
        log.info("Workspace {} Organization {} ", workspace.getId().toString(),
                workspace.getOrganization().getId().toString());
        workspace.setLocked(locked);
        workspaceRepository.save(workspace);
        String organizationName = workspace.getOrganization().getName();
        Map<String, Object> otherAttributes = new HashMap<>();

        otherAttributes.put("locked", false);
        return getWorkspace(organizationName, workspace.getName(), otherAttributes);
    }

    StateData createWorkspaceState(String workspaceId, StateData stateData) {
        Workspace workspace = workspaceRepository.getReferenceById(UUID.fromString(workspaceId));

        byte[] decodedBytes = stateData.getData().getAttributes().get("state").toString().getBytes();
        String terraformState = new String(Base64.getMimeDecoder().decode(decodedBytes));

        //According to API docs json-state is optional so if the value is not available we will upload a default "{}"
        byte[] decodedBytesJson = (stateData.getData().getAttributes().get("json-state") != null) ? stateData.getData().getAttributes().get("json-state").toString().getBytes() : "{}".getBytes(StandardCharsets.UTF_8);
        String terraformStateJson = new String(Base64.getMimeDecoder().decode(decodedBytesJson));

        //upload state to backend storage
        storageTypeService.uploadState(workspace.getOrganization().getId().toString(), workspace.getId().toString(), terraformState);

        //create dummy job
        Job job = new Job();
        job.setWorkspace(workspace);
        job.setOrganization(workspace.getOrganization());
        job.setStatus(JobStatus.completed);
        job = jobRepository.save(job);

        //dummy step
        Step step = new Step();
        step.setId(UUID.randomUUID());
        step.setJob(job);
        step.setName("Dummy State Uploaded");
        step.setStatus(JobStatus.completed);
        step.setStepNumber(100);
        stepRepository.save(step);

        //create dummy history
        History history = new History();
        UUID historyId = UUID.randomUUID();
        history.setId(historyId);
        history.setOutput("");
        history.setJobReference(String.valueOf(job.getId()));
        history.setWorkspace(workspace);
        history = historyRepository.save(history);

        history.setOutput(String
                .format("https://%s/tfstate/v1/organization/%s/workspace/%s/state/%s.json",
                        hostname,
                        workspace.getOrganization().getId().toString(),
                        workspace.getId().toString(),
                        history.getId().toString()));

        //upload json state to backend storage
        storageTypeService.uploadTerraformStateJson(workspace.getOrganization().getId().toString(), workspace.getId().toString(), terraformStateJson, history.getId().toString());

        StateData response = new StateData();
        response.setData(new StateModel());
        response.getData().setId(historyId.toString());
        response.getData().setType("state-versions");

        Map<String, Object> responseAttributes = new HashMap<>();
        responseAttributes.put("vcs-commit-sha", null);
        responseAttributes.put("vcs-commit-url", null);
        responseAttributes.put("hosted-state-download-url", String
                .format("https://%s/tfstate/v1/organization/%s/workspace/%s/state/terraform.tfstate",
                        hostname,
                        workspace.getOrganization().getId().toString(),
                        workspace.getId().toString()));
        responseAttributes.put("hosted-json-state-download-url", String
                .format("https://%s/tfstate/v1/organization/%s/workspace/%s/state/%s.json",
                        hostname,
                        workspace.getOrganization().getId().toString(),
                        workspace.getId().toString(),
                        history.getId().toString()));
        responseAttributes.put("serial", 1);
        response.getData().setAttributes(responseAttributes);

        log.info("Download State URL: {}", String
                .format("https://%s/tfstate/v1/organization/%s/workspace/%s/state/terraform.tfstate",
                        hostname,
                        workspace.getOrganization().getId().toString(),
                        workspace.getId().toString()));

        log.info("Download State JSON URL: {}", String
                .format("https://%s/tfstate/v1/organization/%s/workspace/%s/state/%s.json",
                        hostname,
                        workspace.getOrganization().getId().toString(),
                        workspace.getId().toString(),
                        history.getId().toString()));
        return response;
    }

    StateData getCurrentWorkspaceState(String workspaceId) {
        Workspace workspace = workspaceRepository.getReferenceById(UUID.fromString(workspaceId));

        log.info("Searching for existing terraform state for {} in the storage service", workspace.getId());
        byte[] currentState;
        try {
            currentState = storageTypeService.getCurrentTerraformState(workspace.getOrganization().getId().toString(), workspaceId);
        } catch (Exception ex) {
            log.error("Exception searching state in storage");
            log.error(ex.getMessage());
            currentState = new byte[0];
        }
        if (currentState.length > 0) {
            String historyId = UUID.randomUUID().toString();
            StateData currentStateData = new StateData();
            currentStateData.setData(new StateModel());
            currentStateData.getData().setId(historyId);
            currentStateData.getData().setType("state-versions");

            Map<String, Object> responseAttributes = new HashMap<>();
            responseAttributes.put("vcs-commit-url", null);
            responseAttributes.put("vcs-commit-sha", null);
            responseAttributes.put("hosted-state-download-url", String
                    .format("https://%s/tfstate/v1/organization/%s/workspace/%s/state/terraform.tfstate",
                            hostname,
                            workspace.getOrganization().getId().toString(),
                            workspace.getId().toString()));
            responseAttributes.put("serial", 1);
            currentStateData.getData().setAttributes(responseAttributes);

            return currentStateData;
        } else
            return null;
    }

    ConfigurationData createConfigurationVersion(String workspaceId, ConfigurationData configurationData) {
        log.info("Create Configuration Version {}", configurationData.toString());
        log.info("Speculative {}", configurationData.getData().getAttributes().get("speculative"));
        log.info("Auto Queue Runs {}", configurationData.getData().getAttributes().get("auto-queue-runs"));

        Content content = new Content();
        content.setStatus("pending");
        content.setSource("tfe-api");
        content.setSpeculative((boolean) configurationData.getData().getAttributes().get("speculative"));
        content.setWorkspace(workspaceRepository.getReferenceById(UUID.fromString(workspaceId)));

        content = contentRepository.save(content);
        log.info("New content with id {} saved", content.getId().toString());
        configurationData.getData().setId(content.getId().toString());
        configurationData.getData().setType("configuration-versions");
        configurationData.getData().getAttributes().put("error", null);
        configurationData.getData().getAttributes().put("error-message", null);
        configurationData.getData().getAttributes().put("status", "pending");
        configurationData.getData().getAttributes().put("upload-url", String
                .format("https://%s/remote/tfe/v2/configuration-versions/%s", hostname, content.getId().toString()));
        log.info("upload-url {}", String.format("https://%s/remote/tfe/v2/configuration-versions/%s", hostname,
                content.getId().toString()));
        return configurationData;
    }

    ConfigurationData uploadFile(String contentId, InputStream inputStream) {
        storageTypeService.createContentFile(contentId, inputStream);
        log.info("Searching Content Id {}", contentId);

        Content content = contentRepository.getReferenceById(UUID.fromString(contentId));
        content.setStatus("uploaded");
        contentRepository.save(content);
        return searchConfiguration(contentId);
    }

    byte[] getContentFile(String contentId) {
        return storageTypeService.getContentFile(contentId);
    }

    ConfigurationData searchConfiguration(String contentId) {
        Content content = contentRepository.getReferenceById(UUID.fromString(contentId));
        ConfigurationData configurationData = new ConfigurationData();
        ConfigurationModel configurationModel = new ConfigurationModel();
        configurationModel.setType("configuration-versions");
        configurationModel.setId(content.getId().toString());
        configurationModel.setAttributes(new HashMap<>());
        configurationData.setData(configurationModel);

        configurationData.getData().getAttributes().put("error", null);
        configurationData.getData().getAttributes().put("error-message", null);
        configurationData.getData().getAttributes().put("error-message", null);
        configurationData.getData().getAttributes().put("source", content.getSource());
        configurationData.getData().getAttributes().put("status", content.getStatus());
        configurationData.getData().getAttributes().put("speculative", content.isSpeculative());
        configurationData.getData().getAttributes().put("auto-queue-runs", content.isAutoQueueRuns());

        return configurationData;
    }

    RunsData createRun(RunsData runsData) throws SchedulerException, ParseException {
        String workspaceId = runsData.getData().getRelationships().getWorkspace().getData().getId();
        String configurationId = runsData.getData().getRelationships().getConfigurationVersion().getData().getId();
        boolean isDestroy = runsData.getData().getAttributes().get("is-destroy") != null
                ? (boolean) runsData.getData().getAttributes().get("is-destroy")
                : false;
        log.info("Creating new Terrakube Job");
        log.info("Workspace {} Configuration {}", workspaceId, configurationId);
        Workspace workspace = workspaceRepository.getReferenceById(UUID.fromString(workspaceId));
        workspace.setSource(String.format("https://%s/remote/tfe/v2/configuration-versions/%s/terraformContent.tar.gz",
                hostname, configurationId));
        workspace = workspaceRepository.save(workspace);
        Template template = templateRepository.getByOrganizationNameAndName(
                workspace.getOrganization().getName(),
                getTemplateName(configurationId, isDestroy));
        log.info("Creating Job");
        Job job = new Job();
        job.setWorkspace(workspace);
        job.setOrganization(workspace.getOrganization());
        job.setStatus(JobStatus.pending);
        job.setComments("terraform-cli");
        job.setTemplateReference(template.getId().toString());
        job = jobRepository.save(job);
        log.info("Job Created");
        scheduleJobService.createJobContext(job);
        log.info("Job Context Created");
        return getRun(job.getId(), null);
    }

    private String getTemplateName(String configurationId, boolean isDestroy) {
        // get run speculative if false get Terraform Plan/Apply else just Plan or
        // destroy
        Content content = contentRepository.getReferenceById(UUID.fromString(configurationId));
        if (isDestroy) {
            return "Terraform-Plan/Destroy-Cli";
        } else if (content.isSpeculative()) {
            return "Plan";
        } else {
            return "Terraform-Plan/Apply-Cli";
        }
    }

    RunsData getRun(int runId, String include) {
        log.info("Searching Run {}", runId);
        RunsData runsData = new RunsData();
        RunsModel runsModel = new RunsModel();
        runsModel.setId(String.valueOf(runId));
        runsModel.setType("runs");
        runsModel.setAttributes(new HashMap<>());

        String planStatus = "running";
        Job job = jobRepository.getReferenceById(Integer.valueOf(runId));

        switch (job.getStatus()) {
            case completed:
                planStatus = "finished";
                break;
            case running:
            case queue:
                planStatus = "running";
                break;
            case failed:
                planStatus = "errored";
                break;
        }

        runsModel.getAttributes().put("status", planStatus);
        runsModel.getAttributes().put("has-changes", true);
        runsModel.getAttributes().put("resource-additions", 1);
        runsModel.getAttributes().put("resource-changes", 1);
        runsModel.getAttributes().put("resource-destructions", 0);

        HashMap<String, Object> actions = new HashMap<>();
        actions.put("is-confirmable", true);
        actions.put("is-discardable", false);
        runsModel.getAttributes().put("actions", actions);

        HashMap<String, Object> permissions = new HashMap<>();
        permissions.put("can-apply", true);
        runsModel.getAttributes().put("permissions", permissions);

        runsData.setData(runsModel);
        Relationships relationships = new Relationships();
        org.terrakube.api.plugin.state.model.runs.PlanModel planModel = new org.terrakube.api.plugin.state.model.runs.PlanModel();
        planModel.setData(new Resource());
        planModel.getData().setType("plans");
        planModel.getData().setId(String.valueOf(runId));
        relationships.setPlan(planModel);

        ApplyModel applyModel = new ApplyModel();
        applyModel.setData(new Resource());
        applyModel.getData().setType("applies");
        applyModel.getData().setId(String.valueOf(runId));
        relationships.setApply(applyModel);

        org.terrakube.api.plugin.state.model.runs.WorkspaceModel workspaceModel = new org.terrakube.api.plugin.state.model.runs.WorkspaceModel();
        workspaceModel.setData(new Resource());
        workspaceModel.getData().setId(job.getWorkspace().getId().toString());
        workspaceModel.getData().setType("workspaces");
        relationships.setWorkspace(workspaceModel);

        RunEventsModel runEventsModel = new RunEventsModel();
        runEventsModel.setData(new ArrayList());
        relationships.setRunEventsModel(runEventsModel);

        log.info("Included: {}", include);
        //if(include != null && include.equals("workspace")){
        //    runsData.setIncluded(new ArrayList());
        //    runsData.getIncluded().add(getWorkspace(job.getOrganization().getName(), job.getWorkspace().getName(), new HashMap<>()));
        //}

        runsData.getData().setRelationships(relationships);

        log.info("{}", runsData.toString());
        return runsData;
    }

    RunsData runApply(int runId) {
        Job job = jobRepository.getReferenceById(Integer.valueOf(runId));
        if (job.getStep() != null && !job.getStep().isEmpty()) {
            for (Step step : job.getStep()) {
                if (step.getStepNumber() == 150) {
                    step.setStatus(JobStatus.completed);
                    step.setOutput(String.format("https://%s/tfoutput/v1/organization/%s/job/%s/step/%s", this.hostname,
                            job.getOrganization().getId().toString(), job.getId(), step.getId()));
                    stepRepository.save(step);
                    job.setStatus(JobStatus.pending);
                    jobRepository.save(job);
                    break;
                }
            }
        }
        return getRun(runId, null);
    }

    RunsData runDiscard(int runId) {
        try {
            Job job = jobRepository.getReferenceById(Integer.valueOf(runId));
            job.setStatus(JobStatus.cancelled);
            jobRepository.save(job);
            scheduleJobService.unlockWorkpace(job.getWorkspace().getId());
            scheduleJobService.deleteJobContext(job.getId());
        } catch (ParseException | SchedulerException e) {
            throw new RuntimeException(e);
        }
        return getRun(runId, null);
    }

    PlanRunData getPlan(int planId) {
        PlanRunData plansData = new PlanRunData();
        PlanRunModel planRunModel = new PlanRunModel();
        planRunModel.setId(String.valueOf(planId));
        planRunModel.setType("plans");
        planRunModel.setAttributes(new HashMap<>());
        String planStatus = "pending";

        Job job = jobRepository.getReferenceById(Integer.valueOf(planId));
        if (job.getStep() != null && !job.getStep().isEmpty()) {
            for (Step step : job.getStep()) {
                if (step.getStepNumber() == 100) {
                    switch (step.getStatus()) {
                        case pending:
                            planStatus = "pending";
                            break;
                        case queue:
                        case running:
                            planStatus = "running";
                            break;
                        case completed:
                            planStatus = "finished";
                            break;
                        case failed:
                            planStatus = "errored";
                            break;
                    }
                }
            }
        }

        HashMap<String, Object> actions = new HashMap<>();
        actions.put("is-confirmable", true);
        actions.put("is-discardable", false);
        planRunModel.getAttributes().put("actions", actions);

        planRunModel.getAttributes().put("status", planStatus);
        planRunModel.getAttributes().put("log-read-url",
                String.format("https://%s/remote/tfe/v2/plans/%s/logs", hostname, planId));
        plansData.setData(planRunModel);
        return plansData;
    }

    ApplyRunData getApply(int planId) {
        ApplyRunData applyRunData = new ApplyRunData();
        ApplyRunModel applyModel = new ApplyRunModel();
        applyModel.setId(String.valueOf(planId));
        applyModel.setType("applies");
        applyModel.setAttributes(new HashMap<>());
        String applyStatus = "pending";

        Job job = jobRepository.getReferenceById(Integer.valueOf(planId));
        if (job.getStep() != null && !job.getStep().isEmpty()) {
            for (Step step : job.getStep()) {
                if (step.getStepNumber() == 200) {
                    switch (step.getStatus()) {
                        case pending:
                            applyStatus = "pending";
                            break;
                        case queue:
                        case running:
                            applyStatus = "running";
                            break;
                        case completed:
                            applyStatus = "finished";
                            break;
                        case failed:
                            applyStatus = "errored";
                            break;
                    }
                }
            }
        }
        applyModel.getAttributes().put("status", applyStatus);
        applyModel.getAttributes().put("log-read-url",
                String.format("https://%s/remote/tfe/v2/applies/%s/logs", hostname, planId));
        applyRunData.setData(applyModel);
        return applyRunData;
    }

    byte[] getPlanLogs(int planId) {
        Job job = jobRepository.getReferenceById(Integer.valueOf(planId));
        byte[] logs = "".getBytes();
        TextStringBuilder logsOutput = new TextStringBuilder();
        if (job.getStep() != null && !job.getStep().isEmpty())
            for (Step step : job.getStep()) {
                if (step.getStepNumber() == 100) {
                    log.info("Checking logs for plan: {}", step.getId());

                    try {
                        List<MapRecord> messagesPlan = redisTemplate.opsForStream().read(Consumer.from("CLI", String.valueOf(planId)),
                                StreamReadOptions.empty().noack(),
                                StreamOffset.create(String.valueOf(job.getId()), ReadOffset.lastConsumed()));

                        for (MapRecord mapRecord : messagesPlan) {
                            Map<String, String> streamData = (Map<String, String>) mapRecord.getValue();
                            log.info("Data length {}", streamData.get("output").length());
                            logsOutput.appendln(streamData.get("output"));
                            redisTemplate.opsForStream().acknowledge("CLI", mapRecord);
                        }

                        logs = logsOutput.toString().getBytes(StandardCharsets.UTF_8);
                    } catch (Exception ex) {
                        log.debug(ex.getMessage());
                    }
                }
            }
        return logs;
    }

    byte[] getApplyLogs(int planId) {
        Job job = jobRepository.getReferenceById(Integer.valueOf(planId));
        byte[] logs = "".getBytes();
        TextStringBuilder logsOutputApply = new TextStringBuilder();
        if (job.getStep() != null && !job.getStep().isEmpty())
            for (Step step : job.getStep()) {
                if (step.getStepNumber() == 100) {
                    log.info("Checking logs stepId for apply: {}", step.getId());

                    try {
                        List<MapRecord> messagesApply = redisTemplate.opsForStream().read(Consumer.from("CLI", String.valueOf(planId)),
                                StreamReadOptions.empty().noack(),
                                StreamOffset.create(String.valueOf(job.getId()), ReadOffset.lastConsumed()));

                        for (MapRecord mapRecord : messagesApply) {
                            Map<String, String> streamData = (Map<String, String>) mapRecord.getValue();
                            logsOutputApply.appendln(streamData.get("output"));
                            log.info("{}", streamData.get("output"));
                            redisTemplate.opsForStream().acknowledge("CLI", mapRecord);
                        }

                        logs = logsOutputApply.toString().getBytes(StandardCharsets.UTF_8);
                    } catch (Exception ex) {
                        log.debug(ex.getMessage());
                    }
                }
            }
        return logs;
    }
}
