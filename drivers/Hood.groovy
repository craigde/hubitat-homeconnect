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
 *  Home Connect Hood (Child Device of Home Connection Integration)
 *
 *  Current owner: Craig Dewar (craigde)
 *  Original author:Rangner Ferraz Guimaraes (rferrazguimaraes) for original driver port
 *
 *  Version history
 *  1.0 - Initial commit
 *
 */

import groovy.transform.Field
import groovy.json.JsonSlurper

@Field Utils = Utils_create();
@Field List<String> LOG_LEVELS = ["error", "warn", "info", "debug", "trace"]
@Field String DEFAULT_LOG_LEVEL = LOG_LEVELS[1]
@Field static final Integer eventStreamDisconnectGracePeriod = 30
def driverVer() { return "1.9" }

metadata {
    definition(name: "Home Connect Hood", namespace: "craigde", author: "Craig Dewar") {
        capability "Sensor"
        capability "Switch"
        capability "Initialize"
        capability "FanControl"
        
        command "setLighting", [[name:"Enable Lighting*", type:"ENUM", constraints:["On", "Off"]]]
        command "setLightingBrightness", [[name:"Lighting Brightness*", type:"NUMBER", description:"Lighting Brightness (values between 10 and 100)"]]
        //command "setVentingLevel", [[name:"Venting Level*", type:"ENUM", constraints:["FanOff", "FanStage01", "FanStage02", "FanStage03", "FanStage04", "FanStage05"]]]
        command "setIntensiveLevel", [[name:"Intensive Level*", type:"ENUM", constraints:["IntensiveStageOff", "IntensiveStage1", "IntensiveStage2"]]]
        
        command "deviceLog", [[name: "Level*", type:"STRING", description: "Level of the message"], 
                              [name: "Message*", type:"STRING", description: "Message"]] 
        //command "connectEventStream"
        //command "disconnectEventStream"
        command "startProgram"
        command "stopProgram"
        //command "reset"
        command "optionsList"

        attribute "speed", "enum", ["low","medium-low","medium","medium-high","high","on","off","auto"]
        
        attribute "AvailableProgramsList", "JSON_OBJECT"
        attribute "AvailableOptionsList", "JSON_OBJECT"

        // BSH.Common.Status.RemoteControlActive
        // This status indicates whether the allowance for remote controlling is enabled.
        attribute "RemoteControlActive", "enum", ["true", "false"]

        // BSH.Common.Status.RemoteControlStartAllowed
        // This status indicates whether the remote program start is enabled. 
        // This can happen due to a programmatic change (only disabling), 
        // or manually by the user changing the flag locally on the home appliance, 
        // or automatically after a certain duration - usually 24 hours.
        attribute "RemoteControlStartAllowed", "enum", ["true", "false"]

        // BSH.Common.Status.OperationState
        // This status describes the operation state of the home appliance. 
        attribute "OperationState", "enum", [
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
        ]

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

        attribute "ActiveProgram", "string"
        attribute "SelectedProgram", "string"        

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
        
        attribute "EventStreamStatus", "enum", ["connected", "disconnected"]
        
        attribute "VentingLevel", "string"
        attribute "IntensiveLevel", "string"
        attribute "LightingBrightness", "number"
        attribute "Lighting", "enum", ["true", "false"]
        attribute "LocalControlActive", "enum", ["true", "false"]
        attribute "GreaseFilterMaxSaturationNearlyReached", "string"
        attribute "GreaseFilterMaxSaturationReached", "string"
        attribute "DriverVersion", "string"
    }
    
    preferences {
        section { // General
            
            /*
            input "paramInverted", "enum", title: "Fan Buttons Direction", multiple: false, options: ["0" : "Normal (default)", "1" : "Inverted"], required: false, displayDuringSetup: true
		    input "paramLOW", "number", title: "Low Speed Fan %", multiple: false, defaultValue: "20",  range: "10..100", required: false, displayDuringSetup: true
		    input "paramMEDLOW", "number", title: "Medium-Low Speed Fan %", multiple: false, defaultValue: "40",  range: "10..100", required: false, displayDuringSetup: true
		    input "paramMED", "number", title: "Medium Speed Fan %", multiple: false, defaultValue: "60",  range: "10..100", required: false, displayDuringSetup: true
		    input "paramMEDHIGH", "number", title: "Medium-High Speed Fan %", multiple: false, defaultValue: "80",  range: "10..100", required: false, displayDuringSetup: true
		    input "paramHIGH", "number", title: "High Speed Fan %", multiple: false, defaultValue: "99",  range: "10..100", required: false, displayDuringSetup: true
            */
            
            List<String> availableProgramsList = getAvailableProgramsList()
            if(availableProgramsList.size() != 0)
            {
                input name:"selectedProgram", type:"enum", title: "Select Program", options:availableProgramsList
            }
            
            List<String> availableOptionList = getAvailableOptionsList()
            for(int i = 0; i < availableOptionList.size(); ++i) {
                String titleName = availableOptionList[i]
                String optionName = titleName.replaceAll("\\s","")
                input name:optionName, type:"bool", title: "${titleName}", defaultValue: false 
            }

            input name: "logLevel", title: "Log Level", type: "enum", options: LOG_LEVELS, defaultValue: DEFAULT_LOG_LEVEL, required: true
        }
    }
}

def setLighting(state) {
    parent.setLighting(device, state == "On" ? true : false)
}

def setLightingBrightness(BigDecimal value, BigDecimal min = 10, BigDecimal max = 100) {
    value = (int)Math.min(Math.max(value, min), max)
    //Utils.toLogger("debug", "setLightingBrightness: ${value}")
    parent.setLightingBrightness(device, value)
}

def setVentingLevel(level) {
    parent.setVentingLevel(device, level)
}

def setIntensiveLevel(level) {
    parent.setIntensiveLevel(device, level)
}

def setSpeed(fanspeed) {
    Utils.toLogger("debug", "fanspeed is $fanspeed")
	def value
	
	//speed - ENUM ["low","medium-low","medium","medium-high","high","on","off","auto"]
    // home connect values - ENUM["FanOff", "FanStage01", "FanStage02", "FanStage03", "FanStage04", "FanStage05"]
    switch (fanspeed) {
        case "low":
            Utils.toLogger("debug", "fanspeed low detected")
			sendEvent([name: "speed", value: "low", displayed: true, descriptionText: "fan speed set to $fanspeed"])		
            setVentingLevel("FanStage01")
			break
		case "medium-low":
            Utils.toLogger("debug", "fanspeed medium-low detected")
			sendEvent([name: "speed", value: "medium-low", displayed: true, descriptionText: "fan speed set to $fanspeed"])		
			setVentingLevel("FanStage02")
            break
		case "medium":
            Utils.toLogger("debug", "fanspeed medium detected")
			sendEvent([name: "speed", value: "medium", displayed: true, descriptionText: "fan speed set to $fanspeed"])		
			setVentingLevel("FanStage03")
            break
		case "medium-high":
            Utils.toLogger("debug", "fanspeed medium-high detected")
			sendEvent([name: "speed", value: "medium-high", displayed: true, descriptionText: "fan speed set to $fanspeed"])		
			setVentingLevel("FanStage04")
            break
		case "high":
            Utils.toLogger("debug", "fanspeed high detected")
			sendEvent([name: "speed", value: "high", displayed: true, descriptionText: "fan speed set to $fanspeed"])		
			setVentingLevel("FanStage05")
            break
		case "off":
            Utils.toLogger("debug", "speed off detected")
			sendEvent([name: "speed", value: "off", displayed: true, descriptionText: "fan speed set to off"])		
			setVentingLevel("FanOff")
			break
		case "on":
            Utils.toLogger("debug", "speed on detected")
			sendEvent([name: "speed", value: "high", displayed: true, descriptionText: "fan speed set to high"])		
			setVentingLevel("FanStage05") // max speed
			break
		case "auto":
			//if (logEnable) log.debug "speed auto detected"	
			//sendEvent([name: "speed", value: "on", displayed: true, descriptionText: "fan speed set to $fanspeed"])		
			//on()
            Utils.toLogger("warn", "Speed AUTO requested. This doesn't do anything in this driver right now.")
			break
		default:
            Utils.toLogger("error", "Fan speed option not supported.")
            break
    }
}

def cycleSpeed(){
    def cycleSpeeds = ["off","low","medium-low","medium","high"]
    //find the current index
    def currentIndex = cycleSpeeds.indexOf(device.currentValue("speed") == null ? "off" : device.currentValue("speed"))
    //calculate the next index 
    def nextIndex = (currentIndex + 1 > cycleSpeeds.size() - 1) ? 0 : currentIndex + 1
    //set the speed
    Utils.toLogger("trace", "Executed 'cycleSpeed'. Next speed: ${cycleSpeeds[nextIndex]}")
    setSpeed(cycleSpeeds[nextIndex])
}

void startProgram() {
    if(selectedProgram != null) {
        def programToSelect = state.foundAvailablePrograms.find { it.name == selectedProgram }
        if(programToSelect) {
            parent.startProgram(device, programToSelect.key)
        }
    }
}

void stopProgram() {
    parent.stopProgram(device)
}

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

    setCurrentProgram()    
    updateAvailableOptionsList()
    setCurrentProgramOptions()
}

void uninstalled() {
    disconnectEventStream()
}

void setCurrentProgram() {
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

void optionsList() {
    Utils.toLogger("debug", "optionsList")
    
    def VentingLevels = parent.getActiveProgramOption(device, "Cooking.Common.Option.Hood.VentingLevel")
    Utils.toLogger("debug", "VentingLevels: ${VentingLevels}")

    def IntensiveLevels = parent.getActiveProgramOption(device, "Cooking.Common.Option.Hood.IntensiveLevel")
    Utils.toLogger("debug", "IntensiveLevels: ${IntensiveLevels}")
}

void reset() {
    Utils.toLogger("debug", "reset")
    unschedule()
    sendEvent(name: "EventStreamStatus", value: "disconnected", displayed: true, isStateChange: true)
    disconnectEventStream()
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
            atomicState.oStartTokenExpires = now() + 60_000 // 60 seconds
            setEventStreamStatusToConnected()
            break        
        case 'STOP':
            if(now() >= atomicState.oStartTokenExpires) { // stream started recently so check if we need to ignore any STOP event
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
