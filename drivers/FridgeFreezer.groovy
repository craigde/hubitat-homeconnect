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
 *  Home Connect FridgeFreezer (Child Device of Home Conection Integration)
 *
 *  Author: Rangner Ferraz Guimaraes (rferrazguimaraes)
 *  Date: 2021-11-28
 *  Version: 1.0 - Initial commit
 *  Version: 1.1 - Removed unnecessary information for device
 *  Version: 1.2 - Fixed program errors
 *  Version: 1.3 - Added better handling of STOP events from event stream
 *  Version: 1.4 - Updating program when pressing 'Initialize' button
 *  Version: 1.5 - Added support for events DoorAlarmFreezer, DoorAlarmRefrigerator and TemperatureAlarmFreezer
 */

import groovy.transform.Field
import groovy.json.JsonSlurper

@Field Utils = Utils_create();
@Field List<String> LOG_LEVELS = ["error", "warn", "info", "debug", "trace"]
@Field String DEFAULT_LOG_LEVEL = LOG_LEVELS[1]
@Field static final Integer eventStreamDisconnectGracePeriod = 30
def driverVer() { return "1.5" }

metadata {
    definition(name: "Home Connect FridgeFreezer", namespace: "rferrazguimaraes", author: "Rangner Ferraz Guimaraes") {
        capability "Sensor"
        //capability "Switch"
        capability "ContactSensor"
        capability "Initialize"
        
        command "deviceLog", [[name: "Level*", type:"STRING", description: "Level of the message"], 
                              [name: "Message*", type:"STRING", description: "Message"]] 
        //command "connectEventStream"
        //command "disconnectEventStream"
        //command "startProgram"
        //command "stopProgram"
        //command "reset"

        //attribute "AvailableProgramsList", "JSON_OBJECT"
        //attribute "AvailableOptionsList", "JSON_OBJECT"

        // BSH.Common.Status.RemoteControlActive
        // This status indicates whether the allowance for remote controlling is enabled.
        //attribute "RemoteControlActive", "enum", ["true", "true"]

        // BSH.Common.Status.RemoteControlStartAllowed
        // This status indicates whether the remote program start is enabled. 
        // This can happen due to a programmatic change (only disabling), 
        // or manually by the user changing the flag locally on the home appliance, 
        // or automatically after a certain duration - usually 24 hours.
        //attribute "RemoteControlStartAllowed", "enum", ["true", "false"]

        // BSH.Common.Status.OperationState
        // This status describes the operation state of the home appliance. 
        /*attribute "OperationState", "enum", [
            // Key: BSH.Common.EnumType.OperationState.Inactive
            // Description: Home appliance is inactive. It could be switched off or in standby.
            "Inactive",

            // Key: BSH.Common.EnumType.OperationState.Ready
            // Description: Home appliance is switched on. No program is active.
            "Ready",

            // Key: BSH.Common.EnumType.OperationState.DelayedStart
            // Description: A program has been activated but has not started yet.
            "DelayedStart",

            // Key: BSH.Common.EnumType.OperationState.Run
            // Description: A program is currently active.
            "Run",

            // Key: BSH.Common.EnumType.OperationState.Pause
            // Description: The active program has been paused.
            "Pause",

            // Key: BSH.Common.EnumType.OperationState.ActionRequired
            // Description: The active program requires a user interaction.
            "ActionRequired",

            // Key: BSH.Common.EnumType.OperationState.Finished
            // Description: The active program has finished or has been aborted successfully.
            "Finished",

            // Key: BSH.Common.EnumType.OperationState.Error
            // Description: The home appliance is in an error state.
            "Error",

            // Key: BSH.Common.EnumType.OperationState.Aborting
            // Description: The active program is currently aborting.
            "Aborting",
        ]*/

        // BSH.Common.Status.DoorState
        // This status describes the state of the door of the home appliance. 
        // A change of that status is either triggered by the user operating 
        // the home appliance locally (i.e. opening/closing door) or 
        // automatically by the home appliance (i.e. locking the door).
        //
        // Please note that the door state of coffee machines is currently 
        // only available for American coffee machines. 
        // All other coffee machines will be supported soon.
        attribute "DoorState", "enum", [
            //  Key: BSH.Common.EnumType.DoorState.Open
            // Description: The door of the home appliance is open.
            "Open",

            // Key: BSH.Common.EnumType.DoorState.Closed
            // Description: The door of the home appliance is closed but not locked.
            "Closed",

            //  Key: BSH.Common.EnumType.DoorState.Locked
            // Description: The door of the home appliance is locked.
            "Locked",
        ]

        //attribute "ActiveProgram", "string"
        //attribute "SelectedProgram", "string"        

        attribute "PowerState", "enum", [
            // Key: BSH.Common.EnumType.PowerState.Off
            // Description: The home appliance switched to off state but can 
            // be switched on by writing the value BSH.Common.EnumType.PowerState.
            // On to this setting.
            "Off",

            // Key: BSH.Common.EnumType.PowerState.On
            // Description: The home appliance switched to on state. 
            // You can switch it off by writing the value BSH.Common.EnumType.PowerState.Off 
            // or BSH.Common.EnumType.PowerState.Standby depending on what is supported by the appliance.
            "On",

            //  Key: BSH.Common.EnumType.PowerState.Standby
            // Description: The home appliance went to standby mode.
            // You can switch it on or off by changing the value of this setting appropriately.
            "Standby"
        ]

        attribute "EventPresentState", "enum", [
            // Key: BSH.Common.EnumType.EventPresentState.Present
            // Description: The event occurred and is present.
            "Event active",

            // Key: BSH.Common.EnumType.EventPresentState.Off
            // Description: The event is off.
            "Off",

            //  Key: BSH.Common.EnumType.EventPresentState.Confirmed
            // Description: The event has been confirmed by the user.
            "Confirmed"
        ]
        
        attribute "DoorAlarmFreezer", "string"
        attribute "DoorAlarmRefrigerator", "string"
        attribute "TemperatureAlarmFreezer", "string"
        
        attribute "EventStreamStatus", "enum", ["connected", "disconnected"]
        attribute "DriverVersion", "string"
    }
    
    preferences {
        section { // General
            /*List<String> availableProgramsList = getAvailableProgramsList()
            if(availableProgramsList.size() != 0)
            {
                input name:"selectedProgram", type:"enum", title: "Select Program", options:availableProgramsList
            }
            
            List<String> availableOptionList = getAvailableOptionsList()
            for(int i = 0; i < availableOptionList.size(); ++i) {
                String titleName = availableOptionList[i]
                String optionName = titleName.replaceAll("\\s","")
                input name:optionName, type:"bool", title: "${titleName}", defaultValue: false 
            }*/

            input name: "logLevel", title: "Log Level", type: "enum", options: LOG_LEVELS, defaultValue: DEFAULT_LOG_LEVEL, required: true
        }
    }
}

/*void startProgram() {
    if(selectedProgram != null) {
        def programToSelect = state.foundAvailablePrograms.find { it.name == selectedProgram }
        if(programToSelect) {
            parent.startProgram(device, programToSelect.key)
        }
    }
}

void stopProgram() {
    parent.stopProgram(device);
}*/

void initialize() {
    Utils.toLogger("debug", "initialize()")
    intializeStatus()
    //runEvery1Minute("intializeStatus")
}

void installed() {
    Utils.toLogger("debug", "installed()")
    intializeStatus()
}

void updated() {
    Utils.toLogger("debug", "updated()")

    //setCurrentProgram()    
    //updateAvailableOptionsList()
    //setCurrentProgramOptions()
}

void uninstalled() {
    disconnectEventStream()
}

/*void setCurrentProgram() {
    // set current program
    if(selectedProgram != null) {
        def programToSelect = state.foundAvailablePrograms.find { it.name == selectedProgram }
        if(programToSelect) {
            parent.setSelectedProgram(device, programToSelect.key)
        }
    }    
}

void setCurrentProgramOptions() {
    // set current program option    
    List<String> availableOptionList = getAvailableOptionsList()
    for(int i = 0; i < availableOptionList.size(); ++i) {
        String optionTitle = availableOptionList[i]
        String optionName = optionTitle.replaceAll("\\s","")
        bool optionValue = settings."${optionName}"
        parent.setSelectedProgramOption(device, programOption.key, optionValue)
    }
}

void updateAvailableProgramList() {
    state.foundAvailablePrograms = parent.getAvailableProgramList(device)
    Utils.toLogger("debug", "updateAvailableProgramList state.foundAvailablePrograms: ${state.foundAvailablePrograms}")
    def programList = state.foundAvailablePrograms.collect { it.name }
    Utils.toLogger("debug", "getAvailablePrograms programList: ${programList}")
    sendEvent(name:"AvailableProgramsList", value: new groovy.json.JsonBuilder(programList), displayed: false)
}

void updateAvailableOptionsList() {
    if(selectedProgram != null) {
        def programToSelect = state.foundAvailablePrograms.find { it.name == selectedProgram }
        if(programToSelect) {
            state.foundAvailableProgramOptions = parent.getAvailableProgramOptionsList(device, programToSelect.key)
            def programOptionsList = state.foundAvailableProgramOptions.collect { it.name }
            sendEvent(name:"AvailableOptionsList", value: new groovy.json.JsonBuilder(programOptionsList), displayed: false)
            Utils.toLogger("debug", "updateAvailableOptionList programOptionsList: ${programOptionsList}")
            return
        }
    }

    state.foundAvailableProgramOptions = []
    sendEvent(name:"AvailableOptionsList", value: [], displayed: false)
}

List<String> getAvailableProgramsList() {
    String json = device?.currentValue("AvailableProgramsList")
    if (json != null) {
        return parseJson(json)
    }
    return []
}

List<String> getAvailableOptionsList() {
    String json = device?.currentValue("AvailableOptionsList")
    if (json != null) {
        return parseJson(json)
    }    
    return []
}

def on() {
    parent.setPowertate(device, true)
}

def off() {
    parent.setPowertate(device, false)
}
*/

void reset() {    
    Utils.toLogger("debug", "reset")
    unschedule()
    sendEvent(name: "EventStreamStatus", value: "disconnected", displayed: true, isStateChange: true)
    disconnectEventStream()
}

void intializeStatus() {
    Utils.toLogger("debug", "Initializing the status of the device")

    //updateAvailableProgramList()
    //updateAvailableOptionsList()
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
        //disconnectEventStream()
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
          state.connectionRetryTime = 900 // cap retry time at 15 minutes
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
            //Utils.toLogger("info", "Event Stream connected")
            break
        
        case 'STOP':
            Utils.toLogger("debug", "eventStreamDisconnectGracePeriod: ${eventStreamDisconnectGracePeriod}")
            runIn(eventStreamDisconnectGracePeriod, "setEventStreamStatusToDisconnected")
            //Utils.toLogger("info", "Event Stream disconnected")
            break

        default:
            Utils.toLogger("error", "Received unhandled Event Stream status message: ${text}")
            runIn(eventStreamDisconnectGracePeriod, "setEventStreamStatusToDisconnected")
            break
    }
}

void parse(String text) {
    Utils.toLogger("debug", "Received eventstream message: ${text}")  
    parent.processMessage(device, text)
    sendEvent(name: "DriverVersion", value: driverVer())
}

def deviceLog(level, msg) {
    Utils.toLogger(level, msg)
}

/**
 * Simple utilities for manipulation
 */

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
