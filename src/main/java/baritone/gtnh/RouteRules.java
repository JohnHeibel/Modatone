// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh;

import baritone.Baritone;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.SettingsUtil;
import baritone.compat.IBlockState;
import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BlockStateInterface;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.minecraft.entity.Entity;

/**
 * Route rules: what a cell costs the path search on top of the move into it, as named rules the model reads and changes
 * whenever it likes (nav.route_rules). A rule matches a cell when every test in its where holds; a move into a matching cell
 * then costs (cost + add) × factor, or cannot be made at all (never). The adds of all matching rules add up and their
 * factors multiply. Built in are the costs the search has always applied (mobs, spawners, underwater, current_to_harm)
 * with their old values; the model's own rules sit beside them. A search uses the rules as they were when it began.
 */
public final class RouteRules {
    private RouteRules(){}
    static final Set<String> TESTS=Set.of("dimension","box","sphere","near_entities","near_blocks","beside_blocks","drop","open_above","submerged","current_to_harm");
    /** The settings these rules replaced, and where each one went. */
    static final Map<String,String> MOVED=Map.of("avoidance","enabled on the route rules mobs and spawners",
        "mobavoidancecoefficient","route rule mobs factor","mobavoidanceradius","route rule mobs where.near_entities.radius",
        "mobspawneravoidancecoefficient","route rule spawners factor","mobspawneravoidanceradius","route rule spawners where.near_blocks.radius",
        "submergedpenalty","route rule underwater add","currenthazardpenalty","route rule current_to_harm add");
    /** The settings that shape a route besides these rules, shown with them (changed with nav.settings while idle). */
    static final List<String> SETTINGS=List.of("allowBreak","allowPlace","allowParkour","allowParkourPlace","allowParkourAscend","allowDownward",
        "maxFallHeightNoWater","jumpPenalty","jumpOverHarmPenalty","blockPlacementPenalty","blockBreakAdditionalPenalty","walkOnWaterOnePenalty",
        "backtrackCostFavoringCoefficient","breathSafety","hazards","standOn","neverStandOn","blocksToDisallowBreaking");

    /** A near test: within radius of what it names, where those were when the search began. */
    record Near(List<Predicate<Entity>> entities,Predicate<IBlockState> blocks,int radius){}
    record Where(Map<String,Object> source,Integer dimension,int[] min,int[] max,int[] center,int sphere,Near nearEntities,Near nearBlocks,
                 Predicate<IBlockState> beside,int besideWithin,int dropDepth,int dropWithin,int openAbove,Boolean submerged,Boolean current){
        /** Whether a test reads the move rather than the cell alone (so the cell's answer cannot be kept). */
        boolean readsMove(){return submerged!=null||current!=null;}
    }
    public record Rule(String name,Where where,double factor,double add,boolean never,boolean enabled){
        Map<String,Object> json(){
            Map<String,Object> out=new LinkedHashMap<>();out.put("name",name);out.put("where",where.source());
            out.put("factor",factor);out.put("add",add);out.put("never",never);out.put("enabled",enabled);out.put("builtin",DEFAULTS.containsKey(name));
            return out;
        }
    }

    private static Map<String,Object> spec(Object... kv){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<kv.length;i+=2)m.put((String)kv[i],kv[i+1]);return m;}
    private static final Map<String,Map<String,Object>> DEFAULTS=new LinkedHashMap<>();
    static {
        DEFAULTS.put("mobs",spec("name","mobs","where",spec("near_entities",spec("match",List.of(spec("class","net.minecraft.entity.monster.IMob")),"radius",8)),"factor",1.5,"enabled",false));
        DEFAULTS.put("spawners",spec("name","spawners","where",spec("near_blocks",spec("match",List.of("minecraft:mob_spawner"),"radius",16)),"factor",2,"enabled",false));
        DEFAULTS.put("underwater",spec("name","underwater","where",spec("submerged",true),"add",4,"enabled",true));
        DEFAULTS.put("current_to_harm",spec("name","current_to_harm","where",spec("current_to_harm",true),"add",20,"enabled",true));
    }
    private static volatile List<Rule> rules=defaults();
    private static final Map<String,Integer> applied=new ConcurrentHashMap<>();

    static List<Rule> defaults(){List<Rule> out=new ArrayList<>();for(var d:DEFAULTS.values())out.add(rule(d,null));return List.copyOf(out);}
    public static List<Rule> rules(){return rules;}
    /** How many cells (moves, for a rule that reads the move) each rule changed in searches since the last reset. */
    public static Map<String,Integer> applied(){return new TreeMap<>(applied);}
    public static void resetApplied(){applied.clear();}

    /** nav.route_rules: get, set (add or change rules by name: given fields replace, the rest stay), remove (the model's), reset (built-ins to their defaults). */
    static synchronized Map<String,Object> call(Baritone engine,Map<String,Object> params){
        String op=String.valueOf(params.getOrDefault("operation","get"));
        List<Rule> next=new ArrayList<>(rules);
        switch(op){
            case "get"->{}
            case "set"->{
                List<?> given=params.get("rules") instanceof List<?> l?l:List.of();
                if(given.isEmpty())throw new IllegalArgumentException("set needs rules: a list of {name, where, factor, add, never, enabled}");
                for(Object o:given){
                    if(!(o instanceof Map<?,?> raw))throw new IllegalArgumentException("each rule is an object");
                    @SuppressWarnings("unchecked") Map<String,Object> m=(Map<String,Object>)raw;
                    String name=String.valueOf(m.get("name"));int at=index(next,name);
                    Rule rule=rule(m,at<0?null:next.get(at));
                    if(at<0)next.add(rule);else next.set(at,rule);
                }
                if(next.size()>64)throw new IllegalArgumentException("at most 64 route rules");
            }
            case "remove"->{for(String name:names(params,true)){
                if(DEFAULTS.containsKey(name))throw new IllegalArgumentException(name+" is built in: set enabled false to turn it off, or reset it");
                int at=index(next,name);if(at<0)throw new IllegalArgumentException("no route rule "+name);next.remove(at);
            }}
            case "reset"->{List<String> names=names(params,false);if(names.isEmpty())names=List.copyOf(DEFAULTS.keySet());
                for(String name:names){
                    if(!DEFAULTS.containsKey(name))throw new IllegalArgumentException(name+" is not built in: remove it instead");
                    next.set(index(next,name),rule(DEFAULTS.get(name),null));
                }}
            default->throw new IllegalArgumentException("operation must be get, set, remove or reset");
        }
        Map<String,Object> out=new LinkedHashMap<>();
        if(!op.equals("get")){
            rules=List.copyOf(next);
            // The path being walked was planned with the old rules: plan it again now if it is safe to stop, else at the next safe point.
            var pathing=engine==null?null:engine.getPathingBehavior(); // none in the tests
            boolean walking=pathing!=null&&(pathing.getCurrent()!=null||pathing.getInProgress().isPresent());
            if(walking)pathing.softCancelIfSafe();
            out.put("replanned",walking);
        }
        out.put("rules",rules.stream().map(Rule::json).toList());
        out.put("applied",applied());
        Map<String,Object> settings=new LinkedHashMap<>();
        for(String name:SETTINGS){var s=Baritone.settings().byLowerName.get(name.toLowerCase(Locale.ROOT));if(s!=null&&!s.isJavaOnly())settings.put(name,SettingsUtil.settingValueToString(s));}
        out.put("settings",settings);
        return out;
    }
    private static int index(List<Rule> list,String name){for(int i=0;i<list.size();i++)if(list.get(i).name().equals(name))return i;return -1;}
    private static List<String> names(Map<String,Object> params,boolean required){
        List<String> out=params.get("names") instanceof List<?> l?l.stream().map(String::valueOf).toList():List.of();
        if(required&&out.isEmpty())throw new IllegalArgumentException("names required");
        return out;
    }

    /** A rule from its JSON, the fields not given taken from the rule it replaces (none: a new rule, which needs a where). */
    static Rule rule(Map<String,Object> m,Rule old){
        for(String key:m.keySet())if(!Set.of("name","where","factor","add","never","enabled","builtin").contains(key))throw new IllegalArgumentException("unknown route rule field "+key);
        String name=String.valueOf(m.get("name"));
        if(!name.matches("[a-z0-9_]{1,32}"))throw new IllegalArgumentException("a rule name is 1..32 of a-z, 0-9 and _");
        if(old==null&&!(m.get("where") instanceof Map))throw new IllegalArgumentException("a new rule needs where (an object of tests; {} matches every cell)");
        @SuppressWarnings("unchecked") Where where=m.get("where") instanceof Map<?,?> w?where((Map<String,Object>)w):old.where();
        double factor=number(m,"factor",old==null?1:old.factor(),0.1,1000),add=number(m,"add",old==null?0:old.add(),0,1000);
        boolean never=bool(m,"never",old!=null&&old.never()),enabled=bool(m,"enabled",old==null||old.enabled());
        if(factor==1&&add==0&&!never)throw new IllegalArgumentException("rule "+name+" changes nothing: give factor, add or never");
        return new Rule(name,where,factor,add,never,enabled);
    }
    static Where where(Map<String,Object> m){
        for(String key:m.keySet())if(!TESTS.contains(key))throw new IllegalArgumentException("unknown test "+key+"; tests: "+new TreeSet<>(TESTS));
        Integer dimension=m.containsKey("dimension")?(int)number(m,"dimension",0,-1e6,1e6):null;
        int[] min=null,max=null,center=null;int sphere=0;
        if(m.get("box") instanceof Map<?,?> b){int[] a=pos(b.get("min"),"box.min"),c=pos(b.get("max"),"box.max");
            min=new int[]{Math.min(a[0],c[0]),Math.min(a[1],c[1]),Math.min(a[2],c[2])};max=new int[]{Math.max(a[0],c[0]),Math.max(a[1],c[1]),Math.max(a[2],c[2])};}
        else if(m.containsKey("box"))throw new IllegalArgumentException("box is {min:[x,y,z], max:[x,y,z]}");
        if(m.get("sphere") instanceof Map<?,?> s){center=pos(s.get("center"),"sphere.center");sphere=(int)number(obj(s),"radius",-1,0,256);}
        else if(m.containsKey("sphere"))throw new IllegalArgumentException("sphere is {center:[x,y,z], radius}");
        Near entities=null,blocks=null;
        if(m.containsKey("near_entities")){var n=child(m,"near_entities");
            entities=new Near(list(n.get("match"),"near_entities.match").stream().map(o->ReferenceFollowJob.selector(obj(o))).toList(),null,(int)number(n,"radius",-1,1,64));}
        if(m.containsKey("near_blocks")){var n=child(m,"near_blocks");
            blocks=new Near(null,BlockRules.matcher(strings(n.get("match"),"near_blocks.match")),(int)number(n,"radius",-1,1,64));}
        Predicate<IBlockState> beside=null;int within=0;
        if(m.containsKey("beside_blocks")){var n=child(m,"beside_blocks");beside=BlockRules.matcher(strings(n.get("match"),"beside_blocks.match"));within=(int)number(n,"within",1,1,2);}
        int depth=0,dropWithin=0;
        if(m.containsKey("drop")){var n=child(m,"drop");depth=(int)number(n,"depth",-1,1,64);dropWithin=(int)number(n,"within",1,1,2);}
        int open=m.containsKey("open_above")?(int)number(m,"open_above",-1,1,64):0;
        Boolean submerged=m.containsKey("submerged")?bool(m,"submerged",true):null,current=m.containsKey("current_to_harm")?bool(m,"current_to_harm",true):null;
        return new Where(Collections.unmodifiableMap(new LinkedHashMap<>(m)),dimension,min,max,center,sphere,entities,blocks,beside,within,depth,dropWithin,open,submerged,current);
    }
    private static Map<String,Object> child(Map<String,Object> m,String key){
        if(!(m.get(key) instanceof Map<?,?>))throw new IllegalArgumentException(key+" is an object");return obj(m.get(key));
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> obj(Object o){
        if(!(o instanceof Map<?,?> m))throw new IllegalArgumentException("expected an object, got "+o);return (Map<String,Object>)m;
    }
    private static List<?> list(Object o,String key){
        if(!(o instanceof List<?> l)||l.isEmpty()||l.size()>64)throw new IllegalArgumentException(key+" is a list of 1..64");return l;
    }
    private static List<String> strings(Object o,String key){return list(o,key).stream().map(String::valueOf).toList();}
    private static int[] pos(Object o,String key){
        List<?> l=list(o,key);if(l.size()!=3)throw new IllegalArgumentException(key+" is [x,y,z]");
        int[] p=new int[3];for(int i=0;i<3;i++){if(!(l.get(i) instanceof Number n))throw new IllegalArgumentException(key+" is [x,y,z]");p[i]=(int)Math.floor(n.doubleValue());}
        return p;
    }
    private static double number(Map<?,?> m,String key,double fallback,double min,double max){
        Object v=m.get(key);
        if(v==null){if(fallback<min)throw new IllegalArgumentException(key+" required");return fallback;}
        if(!(v instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()<min||n.doubleValue()>max)throw new IllegalArgumentException(key+" is a number in "+min+".."+max);
        return n.doubleValue();
    }
    private static boolean bool(Map<String,Object> m,String key,boolean fallback){
        Object v=m.get(key);if(v==null)return fallback;
        if(!(v instanceof Boolean b))throw new IllegalArgumentException(key+" is true or false");return b;
    }

    /**
     * What one search reads: the enabled rules when it began, and where the things their near tests name were then
     * (read here, on the thread that makes the search's context). Null world: no dimension, entities or blocks to find.
     */
    public static Search search(baritone.compat.World world,boolean readWorld,BlockStateInterface bsi){
        List<Rule> on=rules.stream().filter(Rule::enabled).toList();
        Map<Rule,int[]> near=new HashMap<>();
        if(readWorld&&world!=null)for(Rule r:on){
            var w=r.where();List<Integer> at=new ArrayList<>();
            if(w.nearEntities()!=null){var me=net.minecraft.client.Minecraft.getMinecraft().thePlayer;
                for(Entity e:world.loadedEntityList)if(e!=me&&!e.isDead&&w.nearEntities().entities().stream().anyMatch(s->s.test(e))){
                    at.add((int)Math.floor(e.posX));at.add((int)Math.floor(e.boundingBox.minY));at.add((int)Math.floor(e.posZ));}}
            if(w.nearBlocks()!=null)for(Object o:world.nativeWorld.loadedTileEntityList)if(o instanceof net.minecraft.tileentity.TileEntity t&&!t.isInvalid()
                    &&w.nearBlocks().blocks().test(IBlockState.of(world.nativeWorld.getBlock(t.xCoord,t.yCoord,t.zCoord),world.nativeWorld.getBlockMetadata(t.xCoord,t.yCoord,t.zCoord)))){
                at.add(t.xCoord);at.add(t.yCoord);at.add(t.zCoord);}
            if(!at.isEmpty())near.put(r,at.stream().mapToInt(Integer::intValue).toArray());
        }
        return new Search(on,near,world==null?Integer.MIN_VALUE:world.provider.dimensionId,bsi);
    }

    public static final class Search {
        private final List<Rule> cell=new ArrayList<>(),move=new ArrayList<>();
        private final Map<Rule,int[]> near;
        private final int dimension;
        private final BlockStateInterface bsi;
        private final Long2DoubleOpenHashMap factors=new Long2DoubleOpenHashMap(),adds=new Long2DoubleOpenHashMap();
        private Thread owner;
        Search(List<Rule> on,Map<Rule,int[]> near,int dimension,BlockStateInterface bsi){
            for(Rule r:on)(r.where().readsMove()?move:cell).add(r);
            this.near=near;this.dimension=dimension;this.bsi=bsi;factors.defaultReturnValue(Double.NaN);
        }
        public boolean any(){return !cell.isEmpty()||!move.isEmpty();}
        /** The cost of a move into (x,y,z) with the rules applied; COST_INF where a rule says never. currentToHarm is
         *  asked only when a rule tests it. */
        public double apply(double cost,int x,int y,int z,boolean submerged,BooleanSupplier currentToHarm){
            double factor=1,add=0;
            if(!cell.isEmpty()){
                if(owner==null)owner=Thread.currentThread();
                if(owner==Thread.currentThread()){ // one search's own thread keeps each cell's answer; any other works it out again
                    long key=BetterBlockPos.longHash(x,y,z);factor=factors.get(key);
                    if(Double.isNaN(factor)){double[] e=effect(cell,x,y,z,false,null);factor=e[0];factors.put(key,factor);adds.put(key,e[1]);}
                    add=adds.get(key);
                }else{double[] e=effect(cell,x,y,z,false,null);factor=e[0];add=e[1];}
            }
            if(!move.isEmpty()){double[] e=effect(move,x,y,z,submerged,currentToHarm);factor*=e[0];add+=e[1];}
            return factor==Double.POSITIVE_INFINITY?ActionCosts.COST_INF:(cost+add)*factor;
        }
        private double[] effect(List<Rule> list,int x,int y,int z,boolean submerged,BooleanSupplier currentToHarm){
            double factor=1,add=0;
            for(Rule r:list)if(matches(r,x,y,z,submerged,currentToHarm)){
                applied.merge(r.name(),1,Integer::sum);
                if(r.never())return new double[]{Double.POSITIVE_INFINITY,0};
                factor*=r.factor();add+=r.add();
            }
            return new double[]{factor,add};
        }
        private boolean matches(Rule r,int x,int y,int z,boolean submerged,BooleanSupplier currentToHarm){
            Where w=r.where();
            if(w.dimension()!=null&&w.dimension()!=dimension)return false;
            if(w.min()!=null&&(x<w.min()[0]||y<w.min()[1]||z<w.min()[2]||x>w.max()[0]||y>w.max()[1]||z>w.max()[2]))return false;
            if(w.center()!=null&&sq(x-w.center()[0],y-w.center()[1],z-w.center()[2])>w.sphere()*w.sphere())return false;
            if(w.submerged()!=null&&w.submerged()!=submerged)return false;
            if(w.current()!=null&&w.current()!=currentToHarm.getAsBoolean())return false;
            if(w.nearEntities()!=null&&!near(near.get(r),w.nearEntities().radius(),x,y,z))return false;
            if(w.nearBlocks()!=null&&!near(near.get(r),w.nearBlocks().radius(),x,y,z))return false;
            if(w.openAbove()>0&&!openAbove(x,y,z,w.openAbove()))return false;
            if(w.dropDepth()>0&&!dropBeside(x,y,z,w.dropDepth(),w.dropWithin()))return false;
            if(w.beside()!=null&&!beside(x,y,z,w.beside(),w.besideWithin()))return false;
            return true;
        }
        private static int sq(int dx,int dy,int dz){return dx*dx+dy*dy+dz*dz;}
        private static boolean near(int[] at,int radius,int x,int y,int z){
            if(at==null)return false;
            for(int i=0;i<at.length;i+=3)if(sq(x-at[i],y-at[i+1],z-at[i+2])<=radius*radius)return true;
            return false;
        }
        /** Nothing to stop you for n cells straight up from above the head. */
        private boolean openAbove(int x,int y,int z,int n){
            for(int k=2;k<=n+1;k++)if(y+k<256&&!MovementHelper.canWalkThrough(bsi,x,y+k,z))return false;
            return true;
        }
        /** Within that many cells across, an open cell at the feet with a fall of depth or more below it, or lava. */
        private boolean dropBeside(int x,int y,int z,int depth,int within){
            for(int dx=-within;dx<=within;dx++)for(int dz=-within;dz<=within;dz++){
                if(dx==0&&dz==0||!MovementHelper.canWalkThrough(bsi,x+dx,y,z+dz))continue;
                int fell=0;
                for(int yy=y-1;fell<depth;yy--,fell++){
                    if(yy<0)return true;
                    IBlockState s=bsi.get0(x+dx,yy,z+dz);
                    if(MovementHelper.isLava(s.getBlock()))return true;
                    if(MovementHelper.isWater(s.getBlock())||MovementHelper.canWalkOn(bsi,x+dx,yy,z+dz))break;
                }
                if(fell>=depth)return true;
            }
            return false;
        }
        /** A matching block within that many cells of the feet or the head, below and above included. */
        private boolean beside(int x,int y,int z,Predicate<IBlockState> match,int within){
            for(int dy=-within;dy<=1+within;dy++)for(int dx=-within;dx<=within;dx++)for(int dz=-within;dz<=within;dz++)
                if(match.test(bsi.get0(x+dx,y+dy,z+dz)))return true;
            return false;
        }
    }
}
