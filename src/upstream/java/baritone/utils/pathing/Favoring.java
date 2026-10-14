/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Modified by the ModdedBench project (2026) for Minecraft 1.7.10 / GT New Horizons.
 * The original file and its SHA-256 are recorded in META-INF/modbench/UPSTREAM_SOURCES.json.
 */

package baritone.utils.pathing;

import baritone.api.pathing.calc.IPath;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.pathing.movement.CalculationContext;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;

public final class Favoring {

    private final Long2DoubleOpenHashMap favorings;
    /** ModdedBench: the route rules as they were when this search began (baritone.gtnh.RouteRules); they replaced Avoidance. */
    public final baritone.gtnh.RouteRules.Search routes;

    public Favoring(IPlayerContext ctx, IPath previous, CalculationContext context) {
        // ModdedBench: made on the client thread as each search starts, so the rules' near tests read the world here
        this(previous, context, baritone.gtnh.RouteRules.search(context.world, true, context.bsi));
    }

    public Favoring(IPath previous, CalculationContext context) { // create one just from previous path, no mob avoidances
        this(previous, context, baritone.gtnh.RouteRules.search(context.world, false, context.bsi));
    }

    private Favoring(IPath previous, CalculationContext context, baritone.gtnh.RouteRules.Search routes) {
        this.routes = routes;
        favorings = new Long2DoubleOpenHashMap();
        favorings.defaultReturnValue(1.0D);
        double coeff = context.backtrackCostFavoringCoefficient;
        if (coeff != 1D && previous != null) {
            previous.positions().forEach(pos -> favorings.put(BetterBlockPos.longHash(pos), coeff));
        }
    }

    public boolean isEmpty() {
        return favorings.isEmpty();
    }

    public double calculate(long hash) {
        return favorings.get(hash);
    }
}
