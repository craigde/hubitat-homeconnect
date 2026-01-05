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
    Utils.toLogger("debug", "setEventStreamStatusToConnected()")
    unschedule("setEventStreamStatusToDisconnected")
    if (device.currentValue("EventStreamStatus") == "disconnected") { 
        sendEvent(name: "EventStreamStatus", value: "connected", displayed: true, isStateChange: true)
    }
    state.connectionRetryTime = 15
}

void setEventStreamStatusToDisconnected() {
    Utils.toLogger("debug", "setEventStreamStatusToDisconnected()")
    sendEvent(name: "EventStreamStatus", value: "disconnected", displayed: true, isStateChange: true)
    if (state.connectionRetryTime) {
       state.connectionRetryTime *= 2
       if (state.connectionRetryTime > 900) {
          state.connectionRetryTime = 900
       }
    } else {
       state.connectionRetryTime = 15
    }
    Utils.toLogger("debug", "reconnecting EventStream in ${state.connectionRetryTime} seconds")
    runIn(state.connectionRetryTime, "reconnectEventStream")
}

void eventStreamStatus(String text) {
    Utils.toLogger("debug", "Received eventstream status message: ${text}")
    def (String type, String message) = text.split(':', 2)
    switch (type) {    
        case 'START':
            atomicState.oStartTokenExpires = now() + 60_000
            setEventStreamStatusToConnected()
            break        
        case 'STOP':
            if (now() >= atomicState.oStartTokenExpires) {
                Utils.toLogger("debug", "eventStreamDisconnectGracePeriod: ${eventStreamDisconnectGracePeriod}")
                runIn(eventStreamDisconnectGracePeriod, "setEventStreamStatusToDisconnected")
            } else {
                Utils.toLogger("debug", "stream started recently so ignore STOP event")
            }
            break
        default:
            Utils.toLogger("error", "Received unhandled Event Stream status message: ${text}")
            atomicState.oStartTokenExpires = now()
            runIn(eventStreamDisconnectGracePeriod, "setEventStreamStatusToDisconnected")
            break
    }
}

/* ==================== Parse incoming SSE data ==================== */

void parse(String text) {
    Utils.toLogger("debug", "Received eventstream message: ${text}")

    // Handle the SSE data directly in the driver
    try {
        if (text?.startsWith('data:')) {
            String payload = text.substring(5).trim()
            if (payload && payload.startsWith('{')) {
                def obj = new groovy.json.JsonSlurper().parseText(payload)
                def items = (obj?.items instanceof List) ? obj.items : []

                items.each { item ->
                    handleHomeConnectEvent(item)
                }
            }
        }
    } catch (e) {
        Utils.toLogger("error", "parse() payload error: ${e}")
    }

    // Still let parent process (redundant but harmless until parent is refactored)
    parent.processMessage(device, text)
    sendEvent(name: "DriverVersion", value: driverVer())
}

private void handleHomeConnectEvent(Map item) {
    if (!item?.key) return
    
    String key = item.key
    def value = item.value
    String displayValue = item.displayvalue ?: value?.toString()
    
    Utils.toLogger("debug", "handleHomeConnectEvent: key=${key}, value=${value}")
    
    switch(key) {
        // ============ Root/Program ============
        case "BSH.Common.Root.ActiveProgram":
            sendEvent(name: "ActiveProgram", value: displayValue, isStateChange: true)
            break
        case "BSH.Common.Root.SelectedProgram":
            sendEvent(name: "SelectedProgram", value: displayValue, isStateChange: true)
            device.updateSetting("selectedProgram", [value: displayValue, type: "enum"])
            break
            
        // ============ Status ============
        case "BSH.Common.Status.DoorState":
            String doorState = value?.tokenize('.')?.last() ?: displayValue
            sendEvent(name: "DoorState", value: doorState, isStateChange: true)
            break
            
        case "BSH.Common.Status.OperationState":
            String opState = value?.substring(value?.lastIndexOf(".") + 1)
            sendEvent(name: "OperationState", value: opState, isStateChange: true)
            if (opState in ["Ready", "Inactive"]) {
                resetProgramTimers()
            }
            break
            
        case "BSH.Common.Status.LocalControlActive":
            sendEvent(name: "LocalControlActive", value: value?.toString(), isStateChange: true)
            break
        case "BSH.Common.Status.RemoteControlActive":
            sendEvent(name: "RemoteControlActive", value: value?.toString(), isStateChange: true)
            break
        case "BSH.Common.Status.RemoteControlStartAllowed":
            sendEvent(name: "RemoteControlStartAllowed", value: value?.toString(), isStateChange: true)
            break
            
        // ============ Settings ============
        case "BSH.Common.Setting.PowerState":
            String pwr = value?.tokenize('.')?.last() ?: displayValue
            sendEvent(name: "PowerState", value: pwr, isStateChange: true)
            break
            
        // ============ Options / Timing ============
        case "BSH.Common.Option.RemainingProgramTime":
            handleRemainingTime(value)
            break
        case "BSH.Common.Option.ElapsedProgramTime":
            handleElapsedTime(value)
            break
        case "BSH.Common.Option.ProgramProgress":
            Integer pct = (value instanceof Number) ? value.toInteger() : 0
            sendEvent(name: "ProgramProgress", value: pct, isStateChange: true)
            break
            
        // ============ Events ============
        case "BSH.Common.Event.ProgramFinished":
            sendEvent(name: "EventPresentState", value: displayValue, isStateChange: true)
            resetProgramTimers()
            break
        case "BSH.Common.Event.ProgramAborted":
            sendEvent(name: "EventPresentState", value: displayValue, isStateChange: true)
            resetProgramTimers()
            break
            
        // ============ CoffeeMaker specific events ============
        case "ConsumerProducts.CoffeeMaker.Event.BeanContainerEmpty":
            sendEvent(name: "BeanContainerEmpty", value: value?.substring(value?.lastIndexOf(".") + 1), isStateChange: true)
            break
        case "ConsumerProducts.CoffeeMaker.Event.WaterTankEmpty":
            sendEvent(name: "WaterTankEmpty", value: value?.substring(value?.lastIndexOf(".") + 1), isStateChange: true)
            break
        case "ConsumerProducts.CoffeeMaker.Event.DripTrayFull":
            sendEvent(name: "DripTrayFull", value: value?.substring(value?.lastIndexOf(".") + 1), isStateChange: true)
            break
            
        default:
            Utils.toLogger("trace", "Unhandled event: ${key} = ${value}")
            break
    }
}

private void handleRemainingTime(def value) {
    Integer secs = (value instanceof Number) ? value.toInteger() : 0
    
    if (secs == 0) {
        String opState = device.currentValue("OperationState") ?: ""
        Integer progress = (device.currentValue("ProgramProgress") ?: 0) as Integer
        String pwrState = device.currentValue("PowerState") ?: ""
        
        boolean acceptZero = opState in ['Finished','Inactive','Ready','Error','Aborting'] || 
                            progress >= 100 || 
                            pwrState == 'Off'
        
        if (!acceptZero) {
            Utils.toLogger("debug", "Ignoring transient RemainingProgramTime=0 during Run")
            return
        }
    }
    
    String hhmm = formatHHMMFromSeconds(secs)
    sendEvent(name: "RemainingProgramTime", value: hhmm, isStateChange: true)
    sendEvent(name: "remainingTime", value: secs, isStateChange: true)
    sendEvent(name: "remainingTimeDisplay", value: hhmm, isStateChange: true)
}

private void handleElapsedTime(def value) {
    Integer secs = (value instanceof Number) ? value.toInteger() : 0
    String hhmm = formatHHMMFromSeconds(secs)
    sendEvent(name: "ElapsedProgramTime", value: hhmm, isStateChange: true)
    sendEvent(name: "elapsedTime", value: secs, isStateChange: true)
    sendEvent(name: "elapsedTimeDisplay", value: hhmm, isStateChange: true)
}

private void resetProgramTimers() {
    sendEvent(name: "RemainingProgramTime", value: "00:00", isStateChange: true)
    sendEvent(name: "ProgramProgress", value: 0, isStateChange: true)
    sendEvent(name: "ElapsedProgramTime", value: "00:00", isStateChange: true)
    sendEvent(name: "remainingTime", value: 0, isStateChange: true)
    sendEvent(name: "remainingTimeDisplay", value: "00:00", isStateChange: true)
    sendEvent(name: "elapsedTime", value: 0, isStateChange: true)
    sendEvent(name: "elapsedTimeDisplay", value: "00:00", isStateChange: true)
}

private String formatHHMMFromSeconds(Integer secs) {
    if (secs == null || secs < 0) secs = 0
    Integer h = (int)(secs / 3600)
    Integer m = (int)((secs % 3600) / 60)
    return "${String.format('%02d', h)}:${String.format('%02d', m)}"
}

/* ==================== Misc ==================== */

def deviceLog(level, msg) {
    Utils.toLogger(level, msg)
}

/* ==================== Utilities ==================== */

def Utils_create() {
    def instance = [:];
    
    instance.toLogger = { level, msg ->
        if (level && msg) {
            Integer levelIdx = LOG_LEVELS.indexOf(level);
            Integer setLevelIdx = LOG_LEVELS.indexOf(logLevel);
            if (setLevelIdx < 0) {
                setLevelIdx = LOG_LEVELS.indexOf(DEFAULT_LOG_LEVEL);
            }
            if (levelIdx <= setLevelIdx) {
                log."${level}" "${device.displayName} ${msg}";
            }
        }
    }

    return instance;
}
