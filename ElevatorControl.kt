package com.example.brookselevatoraccess

class ElevatorControl {

    private var currentFloor: Int = 1 //Variable for initializing the current floor value to be one.

    //Function for telling the robotic arm which floor number to press
    fun moveToFloor(targetFloor: Int) {
        if(targetFloor != currentFloor) {
            currentFloor = targetFloor
        }
    }

    fun getCurrentFloor(): Int {
        return currentFloor
    }

}