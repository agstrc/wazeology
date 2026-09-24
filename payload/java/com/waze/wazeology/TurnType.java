package com.waze.wazeology;

/** Turn icon id -> byte 17 of the 0x14 frame. Ids match the cluster's 0x14 protocol. */
public enum TurnType {
    STRAIGHT(0),
    LEFT(1),
    RIGHT(2),
    SHARP_LEFT(3),
    SHARP_RIGHT(4),
    SLIGHT_LEFT(5),
    SLIGHT_RIGHT(6),
    U_TURN_LEFT(7),
    U_TURN_RIGHT(8),
    FORK_LEFT(9),
    FORK_RIGHT(10),
    FORK_MIDDLE(11),
    RAMP_ON_LEFT(12),
    RAMP_ON_RIGHT(13),
    RAMP_OFF_LEFT(14),
    RAMP_OFF_RIGHT(15),
    MERGE_LEFT(16),
    MERGE_RIGHT(17),
    ROUNDABOUT_CW_EXIT_1(18),
    ROUNDABOUT_CW_EXIT_2(19),
    ROUNDABOUT_CW_EXIT_3(20),
    ROUNDABOUT_CW_EXIT_4(21),
    ROUNDABOUT_CW_EXIT_5(22),
    ROUNDABOUT_CW_EXIT_6(23),
    ROUNDABOUT_CW_EXIT_7(24),
    ROUNDABOUT_CW_EXIT_8(25),
    ROUNDABOUT_CW_EXIT_9(26),
    ROUNDABOUT_CW_EXIT_10(27),
    ROUNDABOUT_CW_EXIT_11(28),
    ROUNDABOUT_CW_EXIT_12(29),
    ROUNDABOUT_CCW_EXIT_1(30),
    ROUNDABOUT_CCW_EXIT_2(31),
    ROUNDABOUT_CCW_EXIT_3(32),
    ROUNDABOUT_CCW_EXIT_4(33),
    ROUNDABOUT_CCW_EXIT_5(34),
    ROUNDABOUT_CCW_EXIT_6(35),
    ROUNDABOUT_CCW_EXIT_7(36),
    ROUNDABOUT_CCW_EXIT_8(37),
    ROUNDABOUT_CCW_EXIT_9(38),
    ROUNDABOUT_CCW_EXIT_10(39),
    ROUNDABOUT_CCW_EXIT_11(40),
    ROUNDABOUT_CCW_EXIT_12(41),
    DEPARTURE(42),
    DESTINATION(43),
    WAYPOINT_1(44),
    WAYPOINT_2(45),
    WAYPOINT_3(46),
    WAYPOINT_4(47),
    WAYPOINT_5(48),
    WAYPOINT_6(49),
    WAYPOINT_7(50),
    WAYPOINT_8(51),
    WAYPOINT_9(52),
    WAYPOINT_10(53),
    WAYPOINT_11(54),
    WAYPOINT_12(55),
    RESERVE(0xFF);

    public final int id;

    TurnType(int id) {
        this.id = id;
    }

    public static TurnType fromId(int id) {
        for (TurnType t : values()) {
            if (t.id == id) {
                return t;
            }
        }
        throw new IllegalArgumentException("no TurnType with id " + id);
    }
}
