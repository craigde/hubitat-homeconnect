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
 *  Home Connect FridgeFreezer (Child Device of Home Connection Integration)
 *
 *  Current owner: Craig Dewar (craigde)
 *  Original author: Rangner Ferraz Guimaraes (rferrazguimaraes) for original driver port
 *
 *  Version history
 *  1.0 - Initial commit
 *  1.5 - Minor fixes
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
    definition(name: "Home Connect FridgeFreezer", namespace: "craigde", author: "Craig Dewar") {
        capability "Sensor"
        capability "ContactSensor"
        capability "Initialize"
        
        command "deviceLog", [[name: "Level*", type:"STRING", description: "Level of the message"], 
                              [name: "Message*", type:"STRING", description: "Message"]] 

        attribute "DoorState", "enum", ["Open", "Closed", "Locked"]

        attribute "PowerState", "enum", ["Off", "On", "Standby"]

        attribute "EventPresentState", "enum", ["Event active", "Off", "Confirmed"]
        
        // FridgeFreezer specific settings
        attribute "SabbathMode", "enum", ["true", "false"]
        attribute "FreshMode", "enum", ["true", "false"]
        attribute "VacationMode", "enum", ["true", "false"]
        
        // FridgeFreezer specific alarms
        attribute "DoorAlarmFreezer", "string"
        attribute "DoorAlarmRefrigerator", "string"
        attribute "TemperatureAlarmFreezer", "string"
        
        attribute "EventStreamStatus", "enum", ["connected", "disconnected"]
        attribute "DriverVersion", "string"
    }
    
    preferences {
        section {
            input name: "logLevel", title: "Log Level", type: "enum", options: LOG_LEVELS, defaultValue: DEFAULT_LOG_LEVEL, required: true
        }
    }
}

/* ==================== Commands ==================== */

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
}

void uninstalled() {
    disconnectEventStream()
}

/* ==================== Helpers ==================== */

void reset() {    
    Utils.toLogger("debug", "reset")
    unschedule()
    sendEvent(name: "EventStreamStatus", value: "disconnected", displayed: true, isStateChange: true)
    disconnectEventStream()
}

/* ==================== Init & Event Stream ==================== */

void intializeStatus() {
    Utils.toLogger("debug", "Initializing the status of the device")

    parent.intializeStatus(device, false)
    
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
            setEventStreamStatusToConnected()
            break
        
        case 'STOP':
            Utils.toLogger("debug", "eventStreamDisconnectGracePeriod: ${eventStreamDisconnectGracePeriod}")
            runIn(eventStreamDisconnectGracePeriod, "setEventStreamStatusToDisconnected")
            break

        default:
            Utils.toLogger("error", "Received unhandled Event Stream status message: ${text}")
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
        // ============ Status ============
        case "BSH.Common.Status.DoorState":
            String doorState = value?.tokenize('.')?.last() ?: displayValue
            sendEvent(name: "DoorState", value: doorState, isStateChange: true)
            sendEvent(name: "contact", value: (doorState?.toLowerCase() == "open") ? "open" : "closed")
            break
            
        // ============ Settings ============
        case "BSH.Common.Setting.PowerState":
            String pwr = value?.tokenize('.')?.last() ?: displayValue
            sendEvent(name: "PowerState", value: pwr, isStateChange: true)
            break
            
        // ============ FridgeFreezer specific settings ============
        case "Refrigeration.Common.Setting.SabbathMode":
            sendEvent(name: "SabbathMode", value: value?.toString(), isStateChange: true)
            break
        case "Refrigeration.Common.Setting.FreshMode":
            sendEvent(name: "FreshMode", value: value?.toString(), isStateChange: true)
            break
        case "Refrigeration.Common.Setting.VacationMode":
            sendEvent(name: "VacationMode", value: value?.toString(), isStateChange: true)
            break
            
        // ============ FridgeFreezer specific alarms/events ============
        case "Refrigeration.FridgeFreezer.Event.DoorAlarmFreezer":
            sendEvent(name: "DoorAlarmFreezer", value: value?.substring(value?.lastIndexOf(".") + 1), isStateChange: true)
            sendEvent(name: "EventPresentState", value: displayValue, isStateChange: true)
            break
        case "Refrigeration.FridgeFreezer.Event.DoorAlarmRefrigerator":
            sendEvent(name: "DoorAlarmRefrigerator", value: value?.substring(value?.lastIndexOf(".") + 1), isStateChange: true)
            sendEvent(name: "EventPresentState", value: displayValue, isStateChange: true)
            break
        case "Refrigeration.FridgeFreezer.Event.TemperatureAlarmFreezer":
            sendEvent(name: "TemperatureAlarmFreezer", value: value?.substring(value?.lastIndexOf(".") + 1), isStateChange: true)
            sendEvent(name: "EventPresentState", value: displayValue, isStateChange: true)
            break
            
        default:
            Utils.toLogger("trace", "Unhandled event: ${key} = ${value}")
            break
    }
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
