package games.strategy.triplea.ui.unit.scroller;

import games.strategy.engine.data.*;
import games.strategy.triplea.attachments.UnitAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.ui.MouseDetails;
import games.strategy.triplea.ui.panels.map.MapPanel;
import games.strategy.triplea.ui.panels.map.MapSelectionListener;
import games.strategy.triplea.ui.panels.map.UnitSelectionListener;

import javax.annotation.Nullable;
import java.awt.*;
import java.util.List;
import java.util.*;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Unit scroller is a UI component to 'scroll' through units that can be moved. The component is to
 * help players avoid "forgetting to move a unit" by letting them know how many units can be moved
 * and to find them on the map. The scroller provides functionality to center on a territory with
 * movable units, arrows to go to next and previous and a 'sleep' button to skip the current unit.
 * The unit scroller has a center display to show an icon of the units in the current territory.
 *
 * <p>The unit scroller keeps track of state to know which territory is current.
 */
public class VUnitMover {
    public static VUnitMover main;

    private final GameData gameData;
    private final MapPanel mapPanel;
    public VUnitMover(final GameData data, final MapPanel mapPanel, final Supplier<Boolean> parentPanelIsVisible) {
        main = this;

        this.gameData = data;
        this.mapPanel = mapPanel;
        allTerritories_sorted = new ArrayList<>(gameData.getMap().getTerritories());
        allTerritories_sorted.sort((a, b)->{
            var aCenter = this.mapPanel.getUiContext().getMapData().getCenter(a);
            var bCenter = this.mapPanel.getUiContext().getMapData().getCenter(b);
            return Comparator.<Integer>naturalOrder().compare(aCenter.x, bCenter.x);
        });

        mapPanel.addMapSelectionListener(
                new MapSelectionListener() {
                    @Override
                    public void territorySelected(final Territory territory, final MouseDetails md) {
                        Logger.getGlobal().log(Level.INFO, "TerSelected:" + territory.getName());
                    }
                    @Override
                    public void mouseEntered(@Nullable final Territory territory) {}
                    @Override
                    public void mouseMoved(@Nullable final Territory territory, final MouseDetails md) {}
                });
        mapPanel.addUnitSelectionListener(new UnitSelectionListener() {
            @Override
            public void unitsSelected(List<Unit> units, Territory territory, MouseDetails md) {
                Logger.getGlobal().log(Level.INFO, "SelectedUnits:" + units.size());
                //VUnitMover.this.OnUnitsSelected2(territory, units);
            }
        });
        gameData.addGameDataEventListener(GameDataEvent.UNIT_MOVED, ()->{
            VUnitMover.this.OnUnitsSelected2(null, new HashSet<>());
        });
    }
    public void OnUnitsSelected2(Territory ter, Set<Unit> units) {
        if (units.size() == 0) {
            moving_source = null;
            moving_targetOpts.clear();
            moving_target = null;
            UpdateTerritoryHighlights();
            return;
        }

        moving_source = ter;
        moving_targetOpts.clear();
        if (ter != null) {
            var unitsMatchingTerType = (ArrayList<Unit>) units.stream().filter(a->UnitAttachment.get(a.getType()).getIsSea() == ter.isWater()).collect(Collectors.toList());
            var unitsMatchingTerType_minMoveDist = 1;
            if (unitsMatchingTerType.size() > 0) {
                unitsMatchingTerType_minMoveDist = Math.max(1, unitsMatchingTerType.stream().map(a->a.getMovementLeft().intValue()).min(Integer::compareTo).get());
            }

            var neighbors = new ArrayList<>(mapPanel.getData().getMap().getNeighbors(ter, unitsMatchingTerType_minMoveDist));
            neighbors = (ArrayList<Territory>) neighbors.stream().filter(a->{
                // if land/water type matches source, consider valid movement
                var terTypeSame = a.isWater() == ter.isWater();
                if (terTypeSame) return true;

                // if terrain-type differs, but a selected unit is transportable (ie. can cross land<>water) and target is only 1-dist away, consider valid movement
                var hasTransportableUnit = units.stream().anyMatch(b->UnitAttachment.get(b.getType()).getTransportCost() != -1);
                if (hasTransportableUnit && mapPanel.getData().getMap().getDistance(ter, a) == 1) return true;

                return false;
            }).collect(Collectors.toList());
            neighbors.sort((a, b)->{
                var aCenter = VUnitMover.this.mapPanel.getUiContext().getMapData().getCenter(a);
                var bCenter = VUnitMover.this.mapPanel.getUiContext().getMapData().getCenter(b);
                return Comparator.<Integer>naturalOrder().compare(aCenter.x, bCenter.x);
            });
            moving_targetOpts.addAll(neighbors);
            //moving_target = neighbors.get(0);
        }
        UpdateTerritoryHighlights();
    }
    public ArrayList<Territory> allTerritories_sorted;

    public void leftAction() {
        changeTerritorySelection(false);
    }
    public void rightAction() {
        changeTerritorySelection(true);
    }

    private void changeTerritorySelection(final boolean selectNext) {
        var terSet = moving_source != null ? moving_targetOpts : allTerritories_sorted;
        if (!selectNext) {
            final var territories = new ArrayList<>(terSet);
            Collections.reverse(territories);
            terSet = territories;
        }
        var oldTarget = moving_source != null ? moving_target : neutral_target;

        // new focused index is 1 greater
        int newTargetIndex = oldTarget == null ? 0 : terSet.indexOf(oldTarget) + 1;
        if (newTargetIndex >= terSet.size()) {
            // if we are larger than the number of territories, we must start back at zero
            newTargetIndex = 0;
        }
        Territory newTarget = null;
        // make sure we go through every single territory on the board
        for (int i = 0; i < terSet.size(); i++) {
            final Territory t = terSet.get(newTargetIndex);

            var isValidNewTarget = moving_source != null ? true : !UnitScroller.main.getMovableUnits(t).isEmpty();
            var isInView = true;
            if (isValidNewTarget && isInView) {
                newTarget = t;
                //mapPanel.setUnitHighlight(Set.of(matchedUnits));
                break;
            }

            // make sure to cycle through the front half of territories
            if (newTargetIndex + 1 >= terSet.size()) {
                newTargetIndex = 0;
            } else {
                newTargetIndex++;
            }
        }

        if (moving_source != null) {
            moving_target = newTarget;
        } else {
            neutral_target = newTarget;
        }
        //SwingUtilities.invokeLater(() -> UpdateTerritoryHighlights());
        UpdateTerritoryHighlights();
    }

    public static Color neutral_target_color = new Color(0, 1, 0, .5f);
    //public static Color moving_targetOpts_color = new Color(1, 1, 0, .25f);
    //public static Color moving_targetOpts_color = new Color(.7f, 0, 1, 1f);
    public static Color moving_targetOpts_color = new Color(1, 0, 0, 1f);
    public static Color moving_target_color = new Color(.7f, 0, 1, .5f);

    public Territory neutral_target;
    public Territory moving_source;
    public ArrayList<Territory> moving_targetOpts = new ArrayList<>();
    public Territory moving_target;
    public Boolean IsHighlighted(Territory territory) {
        return IsHighlighted(territory.getName());
    }
    public Boolean IsHighlighted(String territoryName) {
        return GetHighlightColor(territoryName) != null;
    }
    public Color GetHighlightColor(String territoryName) {
        // put higher-draw-priority ones first
        if (moving_target != null && moving_target.getName().equals(territoryName)) return moving_target_color;
        if (moving_targetOpts.stream().anyMatch(a->a.getName().equals(territoryName))) return moving_targetOpts_color;
        if (neutral_target != null && neutral_target.getName().equals(territoryName)) return neutral_target_color;
        return null;
    }

    public HashSet<Territory> lastHighlightedTers = new HashSet<>();
    private void UpdateTerritoryHighlights() {
        //mapPanel.highlightTerritory(territory, MapPanel.AnimationDuration.STANDARD, MapPanel.HighlightDelay.SHORT_DELAY, 0);
        //mapPanel.centerOnTerritoryIgnoringMapLock(territory);
        /*mapPanel.highlightedTerritory = territory;
        neutral_target = territory;
        mapPanel.territoryHighlighter.highlight(territory, Integer.MAX_VALUE, Integer.MAX_VALUE);*/

        // clear old
        for (var ter : lastHighlightedTers) {
            mapPanel.clearTerritoryOverlay(ter);
        }

        // set new
        lastHighlightedTers.clear();
        if (neutral_target != null) {
            mapPanel.setTerritoryOverlay(neutral_target, neutral_target_color, neutral_target_color.getAlpha());
            lastHighlightedTers.add(neutral_target);
        }
        for (var ter : moving_targetOpts) {
            mapPanel.setTerritoryOverlay(ter, moving_targetOpts_color, moving_targetOpts_color.getAlpha());
            lastHighlightedTers.add(ter);
        }
        if (moving_target != null) {
            mapPanel.setTerritoryOverlay(moving_target, moving_target_color, moving_target_color.getAlpha());
            lastHighlightedTers.add(moving_target);
        }
        mapPanel.paintImmediately(mapPanel.getBounds());
    }
}
