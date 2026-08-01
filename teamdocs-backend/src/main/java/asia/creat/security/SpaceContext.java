package asia.creat.security;

import asia.creat.entity.SpaceMember;

public class SpaceContext {
    private static final ThreadLocal<SpaceMember> CURRENT_MEMBER = new ThreadLocal<>();

    public static void set(SpaceMember member) { CURRENT_MEMBER.set(member); }
    public static SpaceMember getSpaceMember() { return CURRENT_MEMBER.get(); }
    public static void clear() { CURRENT_MEMBER.remove(); }
}
