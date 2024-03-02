package de.intranda.goobi.plugins;

import java.util.Date;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.lang.StringUtils;
import org.goobi.beans.Process;
import org.goobi.beans.Processproperty;
import org.goobi.beans.Step;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IDelayPlugin;
import org.goobi.production.plugin.interfaces.IStepPlugin;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.enums.StepStatus;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.persistence.managers.StepManager;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;

@Log4j2
@PluginImplementation
public class WorkflowStatusDelayPlugin implements IDelayPlugin, IStepPlugin {

    @Getter
    private String title = "intranda_step_delay_workflowstatus";

    @Getter
    private PluginType type = PluginType.Step;

    @Getter
    private PluginGuiType pluginGuiType = PluginGuiType.NONE;

    @Getter
    private Step step;

    private Process process;

    @Override
    public boolean execute() {
        // check if status is reached - return true;
        if (delayIsExhausted()) {
            return true;
        }
        //else :

        step.setBearbeitungsstatusEnum(StepStatus.INWORK);

        Helper.addMessageToProcessJournal(step.getProzess().getId(), LogType.DEBUG, "started delay.", "delay");

        step.setBearbeitungsbeginn(new Date());

        try {
            StepManager.saveStep(step);
        } catch (DAOException e) {
            log.error("Error while saving the step", e);
        }
        return false;
    }

    @Override
    public String cancel() {
        return null;
    }

    @Override
    public String finish() {
        return null;
    }

    @Override
    public String getPagePath() {
        return null;
    }

    @Override
    public void initialize(Step step, String returnPath) {
        this.step = step;
        process = step.getProzess();
    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null;
    }

    @Override
    public boolean delayIsExhausted() {
        // read configuration
        SubnodeConfiguration config = ConfigPlugins.getProjectAndStepConfig(title, step);
        List<HierarchicalConfiguration> properties = config.configurationsAt("./condition/property");
        List<HierarchicalConfiguration> steps = config.configurationsAt("./condition/step");

        if (properties.isEmpty() && steps.isEmpty()) {
            // nothing configured, abort
            log.error("No conditions found.");
            return false;
        }

        // check if configured properties match
        boolean propertyCheckMatches = checkProperties(properties);
        boolean stepCheckMatches = checkSteps(steps);

        if (propertyCheckMatches && stepCheckMatches) {
            return true;
        } else {
            Helper.addMessageToProcessJournal(step.getProzess().getId(), LogType.DEBUG, "Checked delay: Not all conditions are fulfilled.", "delay");
            return false;
        }
    }

    private boolean checkSteps(List<HierarchicalConfiguration> steps) {

        // check if configured workflow status matches
        if (steps != null && !steps.isEmpty()) {
            for (HierarchicalConfiguration hc : steps) {
                String name = hc.getString("@name");
                String configuredSatus = hc.getString("@status");
                String type = hc.getString("@type", "is");

                boolean conditionMatch = false;
                for (Step step : process.getSchritte()) {
                    if (step.getTitel().equals(name)) {
                        String currentStatus = step.getBearbeitungsstatusEnum().getSearchString().replace("step", "");
                        switch (type) {
                            case "is":
                                conditionMatch = currentStatus.equals(configuredSatus);
                                break;
                            case "not":
                                conditionMatch = !currentStatus.equals(configuredSatus);
                                break;
                            case "atleast":
                                switch (configuredSatus) {
                                    case "locked":
                                        conditionMatch = true;
                                        break;
                                    case "open":
                                        conditionMatch =
                                        step.getBearbeitungsstatusEnum().getValue() > 0 && step.getBearbeitungsstatusEnum().getValue() < 4;
                                        break;
                                    case "inwork":
                                        conditionMatch =
                                        step.getBearbeitungsstatusEnum().getValue() == 2 || step.getBearbeitungsstatusEnum().getValue() == 3;
                                        break;
                                    case "done":
                                        conditionMatch = step.getBearbeitungsstatusEnum().getValue() == 3;
                                        break;
                                    default:
                                        log.info("type 'atleast' cannot be used together with status 'error' or 'deactivated'");
                                        conditionMatch = false;
                                }
                                break;
                            default:
                                log.error("'{}' is no valid type", type);
                                return false;
                        }

                    }
                }

                if (!conditionMatch) {
                    return false;
                }

            }
        }

        return true;
    }

    private boolean checkProperties(List<HierarchicalConfiguration> properties) {
        if (properties != null && !properties.isEmpty()) {
            for (HierarchicalConfiguration hc : properties) {
                String name = hc.getString("@name");
                String propertyValue = hc.getString("@value");
                String propertyCondition = hc.getString("@type", "is");

                if (StringUtils.isBlank(name)) {
                    log.error("Property name is not configured, abort");
                    return false;
                }
                boolean conditionMatches = false;
                Processproperty pp = getProcessProperty(name);
                switch (propertyCondition) {
                    case "missing":
                        if (pp == null || StringUtils.isBlank(pp.getWert())) {
                            conditionMatches = true;
                        }
                        break;

                    case "available":
                        if (pp != null && StringUtils.isNotBlank(pp.getWert())) {
                            conditionMatches = true;
                        }
                        break;

                    case "is":
                        if (pp != null && pp.getWert().trim().equals(propertyValue)) {
                            conditionMatches = true;
                            break;
                        }
                        break;

                    case "not":
                        if (pp == null || !pp.getWert().trim().equals(propertyValue)) {
                            conditionMatches = true;
                            break;
                        }
                        break;
                }
                if (!conditionMatches) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public int getRemainingDelay() {
        return 1;
    }

    @Override
    public void setDelay(long arg0) {
    }

    private Processproperty getProcessProperty(String propertyName) {
        for (Processproperty property : process.getEigenschaften()) {
            if (property.getTitel().equals(propertyName)) {
                return property;
            }
        }
        return null;
    }
}
