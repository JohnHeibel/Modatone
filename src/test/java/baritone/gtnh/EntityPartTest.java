// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh;

import baritone.ForgePlanningTestRunner;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.world.World;
import org.junit.Test;
import static org.junit.Assert.*;

@org.junit.runner.RunWith(ForgePlanningTestRunner.class)
public class EntityPartTest {
    public static class Multipart extends EntityCow {
        Entity[] pieces;
        public Multipart(World world){super(world);}
        @Override public Entity[] getParts(){return pieces;}
    }
    private static <T> T bare(Class<T> type)throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe",true,ClassLoader.getSystemClassLoader());
        var field=unsafe.getDeclaredField("theUnsafe");field.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance",Class.class).invoke(field.get(null),type));
    }
    @Test public void anExplicitPartNeverFallsBackToTheParentOrAnotherPart()throws Exception{
        var parent=bare(Multipart.class);Entity first=bare(EntityCow.class),second=bare(EntityCow.class);
        parent.pieces=new Entity[]{first,second};
        assertSame(parent,FightJob.part(parent,-1));
        assertSame(first,FightJob.part(parent,0));assertSame(second,FightJob.part(parent,1));
        assertThrows(IllegalArgumentException.class,()->FightJob.part(parent,2));
        parent.pieces[1]=null;assertThrows(IllegalArgumentException.class,()->FightJob.part(parent,1));
        parent.pieces=null;assertThrows(IllegalArgumentException.class,()->FightJob.part(parent,0));
    }
}
