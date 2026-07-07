package asia.creat.security;

import asia.creat.entity.SpaceMember;

//上下文持有类
public class SpaceContext {
    private static final ThreadLocal<SpaceMember> CURRENT_MEMBER = new ThreadLocal<>();

    public static void set(SpaceMember member) { CURRENT_MEMBER.set(member); }
    public static SpaceMember get() { return CURRENT_MEMBER.get(); }
    public static void clear() { CURRENT_MEMBER.remove(); }
}
