package games.strategy.triplea.ui.unit.scroller;

import games.strategy.engine.data.*;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.ui.MouseDetails;
import games.strategy.triplea.ui.panels.map.MapPanel;
import games.strategy.triplea.ui.panels.map.MapSelectionListener;
import org.triplea.java.PredicateBuilder;
import org.triplea.java.collections.CollectionUtils;
import org.triplea.swing.CollapsiblePanel;
import org.triplea.swing.DialogBuilder;
import org.triplea.swing.JLabelBuilder;
import org.triplea.swing.SwingComponents;
import org.triplea.swing.jpanel.JPanelBuilder;

import javax.annotation.Nullable;
import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.*;
import java.util.function.Supplier;

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
    //public static VUnitMover main;

    private Territory lastFocusedTerritory;

    private final GameData gameData;
    private final MapPanel mapPanel;

    public VUnitMover(final GameData data, final MapPanel mapPanel, final Supplier<Boolean> parentPanelIsVisible) {
        //main = this;

        this.gameData = data;
        this.mapPanel = mapPanel;

        gameData.addGameDataEventListener(GameDataEvent.GAME_STEP_CHANGED, this::gamePhaseChanged);

        mapPanel.addMapSelectionListener(
                new MapSelectionListener() {
                    @Override
                    public void territorySelected(final Territory territory, final MouseDetails md) {
                    }

                    @Override
                    public void mouseEntered(@Nullable final Territory territory) {
                        if (parentPanelIsVisible.get() && territory != null) {
                            lastFocusedTerritory = territory;
                        }
                    }

                    @Override
                    public void mouseMoved(@Nullable final Territory territory, final MouseDetails md) {
                    }
                });
    }

    private void gamePhaseChanged() {
        lastFocusedTerritory = null;
    }

    public void leftAction() {
        centerOnMovableUnit(false);
    }

    public void rightAction() {
        centerOnMovableUnit(true);
    }

    private void centerOnMovableUnit(final boolean selectNext) {
        List<Territory> allTerritories_orig = gameData.getMap().getTerritories();

        // sort territories by x-pos
        var allTerritories = new ArrayList<>(allTerritories_orig);
        allTerritories.sort((a, b)->{
            /*var aRect = this.mapPanel.getUiContext().getMapData().getBoundingRect(a);
            var bRect = this.mapPanel.getUiContext().getMapData().getBoundingRect(b);
            return Comparator.<Integer>naturalOrder().compare(aRect.x, bRect.x);*/
            var aCenter = this.mapPanel.getUiContext().getMapData().getCenter(a);
            var bCenter = this.mapPanel.getUiContext().getMapData().getCenter(b);
            return Comparator.<Integer>naturalOrder().compare(aCenter.x, bCenter.x);
        });

        if (!selectNext) {
            final var territories = new ArrayList<>(allTerritories);
            Collections.reverse(territories);
            allTerritories = territories;
        }
        // new focused index is 1 greater
        int newFocusedIndex =
                lastFocusedTerritory == null ? 0 : allTerritories.indexOf(lastFocusedTerritory) + 1;
        if (newFocusedIndex >= allTerritories.size()) {
            // if we are larger than the number of territories, we must start back at zero
            newFocusedIndex = 0;
        }
        Territory newFocusedTerritory = null;
        // make sure we go through every single territory on the board
        for (int i = 0; i < allTerritories.size(); i++) {
            final Territory t = allTerritories.get(newFocusedIndex);
            //final List<Unit> matchedUnits = getMovableUnits(t);
            final List<Unit> matchedUnits = UnitScroller.main.getMovableUnits(t);

            if (!matchedUnits.isEmpty()) {
                newFocusedTerritory = t;
                mapPanel.setUnitHighlight(Set.of(matchedUnits));
                break;
            }
            // make sure to cycle through the front half of territories
            if ((newFocusedIndex + 1) >= allTerritories.size()) {
                newFocusedIndex = 0;
            } else {
                newFocusedIndex++;
            }
        }
        if (newFocusedTerritory != null) {
            // When the map is moved, the mouse is moved, we will get a territory
            // selected event that will set the lastFocusedTerritory.
            mapPanel.centerOn(newFocusedTerritory);

            // Do an invoke later here so that these actions are after any map UI events.
            final var selectedTerritory = newFocusedTerritory;
            SwingUtilities.invokeLater(() -> highlightTerritory(selectedTerritory));
        }
    }

    private void highlightTerritory(final Territory territory) {
        if (ClientSetting.unitScrollerHighlightTerritory.getValueOrThrow()) {
            mapPanel.highlightTerritory(
                    territory, MapPanel.AnimationDuration.STANDARD, MapPanel.HighlightDelay.SHORT_DELAY);
        }
    }
}
