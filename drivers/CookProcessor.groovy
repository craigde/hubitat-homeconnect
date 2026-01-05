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
 *  Home Connect Cook Processor (Child Device of Home Connection Integration)
 *
 *  Current owner: Craig Dewar (craigde)
 *  Original author: Rangner Ferraz Guimaraes (rferrazguimaraes) for original driver port
 *
 *  Version history
 *  1.0 - Initial commit
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
    definition(name: "Home Connect CookProcessor", namespace: "craigde", author: "Craig Dewar") {
        capability "Sensor"
        capability "Switch"
        capability "ContactSensor"
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
