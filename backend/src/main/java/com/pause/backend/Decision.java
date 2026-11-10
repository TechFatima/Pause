package com.pause.backend;

/**
 * Decision represents the outcome of a risk assessment.
 *
 * Java concept - enum: an enum is a type that can only be one of a fixed set
 * of named values. Writing "Decision.ALLOW" is clearer and safer than a plain
 * String "ALLOW" because the compiler catches typos and you can compare values
 * with == instead of .equals().
 *
 * The values are declared in order from least severe to most severe.
 * This ordering matters: Java enums have an ordinal() method that returns
 * the position (0, 1, 2). ALLOW=0, HOLD=1, BLOCK=2.
 * We use this to enforce that a standing order can only RAISE the decision,
 * never lower it: we always keep whichever value has the higher ordinal.
 */
public enum Decision {
    ALLOW,   // Payment looks normal — let it through.
    HOLD,    // Something is suspicious — hold the payment and alert the trusted contact.
    BLOCK    // High risk — block immediately.
}
