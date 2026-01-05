/**
 *  Copyright 2021
 *
 *  Based on the original work done by https://github.com/Wattos/hubitat
 * 
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Home Connect CoffeeMaker (Child Device of Home Connection Integration)
 *
 *  Current owner: Craig Dewar (craigde)
 *  Original author: Rangner Ferraz Guimaraes (rferrazguimaraes) for original driver port
 *
 *  Version history
 *  1.0 - Initial commit
 *  1.3 - Minor fixes
 *  2.0 - Driver now handles all event parsing directly (preparation for parent app refactor)
 */

import groovy.transform.Field
import groovy.json.JsonSlurper

@Field Utils = Utils_create();
@Field List<String> LOG_LEVELS = ["error", "warn", "info", "debug", "trace"]
@Field String DEFAULT_LOG_LEVEL = LOG_LEVELS[1]
@Field static final Integer eventStreamDisconnectGracePeriod = 30
def driverVer() { return "2.0" }

metadata {
    definition(name: "Home Connect CoffeeMaker", namespace: "craigde", author: "Craig Dewar") {
        capability "Sensor"
        capability "Switch"
        capability "Initialize"
        
        command "deviceLog", [[name: "Level*", type:"STRING", description: "Level of the message"], 
                              [name: "Message*", type:"STRING", description: "Message"]] 
        command "startProgram"
        command "stopProgram"

        attribute "AvailableProgramsList", "JSON_OBJECT"
        attribute "AvailableOptionsList", "JSON_OBJECT"

        attribute "RemoteControlActive", "enum", ["true", "false"]
        attribute "RemoteControlStartAllowed", "enum", ["true", "false"]
        attribute "LocalControlActive", "enum", ["true", "false"]

        attribute "OperationState", "enum", [
            "Inactive", "Ready", "DelayedStart", "Run", "Pause", "ActionRequired", "Finished", "Error", "Aborting"
        ]

        attribute "DoorState", "enum", ["Open", "Closed", "Locked"]

        attribute "ActiveProgram", "string"
        attribute "SelectedProgram", "string"        

        attribute "PowerState", "enum", ["Off", "On", "Standby"]

        attribute "EventPresentState", "enum", ["Event active", "Off", "Confirmed"]

        attribute "ProgramProgress", "number"
        attribute "RemainingProgramTime", "string"
        attribute "ElapsedProgramTime", "string"

        // Node-RED attributes
        attribute "remainingTime", "number"
        attribute "remainingTimeDisplay", "string"
        attribute "elapsedTime", "number"
        attribute "elapsedTimeDisplay", "string"
        
        // CoffeeMaker specific events
        attribute "BeanContainerEmpty", "string"
        attribute "WaterTankEmpty", "string"
        attribute "DripTrayFull", "string"        
        
        attribute "EventStreamStatus", "enum", ["connected", "disconnected"]
        attribute "DriverVersion", "string"
    }
    
    preferences {
        section {
            List<String> availableProgramsList = getAvailableProgramsList()
            if (availableProgramsList.size() != 0) {
                input name:"selectedProgram", type:"enum", title: "Select Program", options:availableProgramsList
            }
            
            List<String> availableOptionList = getAvailableOptionsList()
            for (int i = 0; i < availableOptionList.size(); ++i) {
                String titleName = availableOptionList[i]
                String optionName = titleName.replaceAll("\\s","")
                input name:optionName, type:"bool", title: "${titleName}", defaultValue: false 
            }

            input name: "logLevel", title: "Log Level", type: "enum", options: LOG_LEVELS, defaultValue: DEFAULT_LOG_LEVEL, required: true
        }
    }
}

/* ==================== Commands ==================== */

void startProgram() {
    String programToUse = selectedProgram ?: device.currentValue("ActiveProgram")
    
    if (programToUse) {
        def programToSelect = state.foundAvailablePrograms.find { it.name == programToUse }
        if (programToSelect) {
            parent.startProgram(device, programToSelect.key)
        } else {
            Utils.toLogger("error", "Program '${programToUse}' not found in available programs")
        }
    } else {
        Utils.toLogger("error", "No program selected")
    }
}

void stopProgram() {
    parent.stopProgram(device)
}

void initialize() {
    Utils.toLogger("debug", "initialize()")
    intializeStatus()
}

void installed() {
    Utils.toLogger("debug", "installed()")
    intializeStatus()
}

void updated() {
    Utils.toLogger("debug", "updated()")
    setCurrentProgram()    
    updateAvailableOptionsList()
    setCurrentProgramOptions()
}

void uninstalled() {
    disconnectEventStream()
}

/* ==================== Helpers: program & options ==================== */

void setCurrentProgram() {
    if (selectedProgram != null) {
        def programToSelect = state.foundAvailablePrograms.find { it.name == selectedProgram }
        if (programToSelect) {
            parent.setSelectedProgram(device, programToSelect.key)
        }
    }    
}

void setCurrentProgramOptions() {
    List<String> availableOptionList = getAvailableOptionsList()
    if (!availableOptionList) return

    for (int i = 0; i < availableOptionList.size(); ++i) {
        String optionTitle = availableOptionList[i]
        String optionName = optionTitle.replaceAll("\\s","")
        Boolean optionValue = (settings?."${optionName}" ?: false)
        
        def programOption = state?.foundAvailableProgramOptions?.find { it.name == optionTitle }
        if (programOption) {
            parent.setSelectedProgramOption(device, programOption.key, optionValue)
        }
    }
}

void updateAvailableProgramList() {
    state.foundAvailablePrograms = parent.getAvailableProgramList(device)
    Utils.toLogger("debug", "updateAvailableProgramList state.foundAvailablePrograms: ${state.foundAvailablePrograms}")
    def programList = state.foundAvailablePrograms.collect { it.name }
    Utils.toLogger("debug", "getAvailablePrograms programList: ${programList}")
    sendEvent(name:"AvailableProgramsList", value: new groovy.json.JsonBuilder(programList).toString(), displayed: false)
}

void updateAvailableOptionsList() {
    if (selectedProgram != null) {
        def programToSelect = state.foundAvailablePrograms.find { it.name == selectedProgram }
        if (programToSelect) {
            state.foundAvailableProgramOptions = parent.getAvailableProgramOptionsList(device, programToSelect.key)
            def programOptionsList = state.foundAvailableProgramOptions.collect { it.name }
            sendEvent(name:"AvailableOptionsList", value: new groovy.json.JsonBuilder(programOptionsList).toString(), displayed: false)
            Utils.toLogger("debug", "updateAvailableOptionList programOptionsList: ${programOptionsList}")
            return
        }
    }

    state.foundAvailableProgramOptions = []
    sendEvent(name:"AvailableOptionsList", value: [], displayed: false)
}

void reset() {    
    Utils.toLogger("debug", "reset")
    unschedule()
    sendEvent(name: "EventStreamStatus", value: "disconnected", displayed: true, isStateChange: true)
    disconnectEventStream()
}

List<String> getAvailableProgramsList() {
    String json = device?.currentValue("AvailableProgramsList")
    if (json != null) return parseJson(json)
    return []
}

List<String> getAvailableOptionsList() {
    String json = device?.currentValue("AvailableOptionsList")
    if (json != null) return parseJson(json)
    return []
}

/* ==================== Switch handling ==================== */

def on() {
    parent.setPowerState(device, true)
}

def off() {
    parent.setPowerState(device, false)
}

/* ==================== Init & Event Stream ==================== */

void intializeStatus() {
    Utils.toLogger("debug", "Initializing the status of the device")

    updateAvailableProgramList()
    updateAvailableOptionsList()
    parent.intializeStatus(device)
    
    try {
        disconnectEventStream()
        connectEventStream()
    } catch (Exception e) {
        Utils.toLogger("error", "intializeStatus() failed: ${e.message}")
        setEventStreamStatusToDisconnected()
    }
}

void connectEventStream() {
    Utils.toLogger("debug", "connectEventStream()")
    parent.getHomeConnectAPI().connectDeviceEvents(device.deviceNetworkId, interfaces);
}

void reconnectEventStream(Boolean notIfAlreadyConnected = true) {
    Utils.toLogger("debug", "reconnectEventStream(notIfAlreadyConnected=$notIfAlreadyConnected)")
    
    if (device.currentValue("EventStreamStatus") == "connected" && notIfAlreadyConnected) {
        Utils.toLogger("debug", "already connected; skipping reconnection")
    } else {
        connectEventStream()
    }
}

void disconnectEventStream() {
    Utils.toLogger("debug", "disconnectEventStream()")
    parent.getHomeConnectAPI().disconnectDeviceEvents(device.deviceNetworkId, interfaces);
}

void setEventStreamStatusToConnected() {
    Utils.toLogger("debu
