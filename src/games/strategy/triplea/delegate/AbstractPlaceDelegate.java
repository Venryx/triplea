/*
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version. This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details. You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */
/**
 * PlaceDelegate.java
 * 
 * Overriding
 * 
 * Subclasses can over ride one of these methods to change the way this class
 * works. playerHasEnoughUnits(...), canProduce(...), canUnitsBePlaced(...)
 * 
 * For a simpler way you can override getProduction(...) which is called in the
 * default canProduce(...) method
 * 
 * Created on November 2, 2001, 12:29 PM
 */
package games.strategy.triplea.delegate;

import games.strategy.common.delegate.BaseDelegate;
import games.strategy.engine.data.Change;
import games.strategy.engine.data.ChangeFactory;
import games.strategy.engine.data.CompositeChange;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.PlayerID;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.message.IRemote;
import games.strategy.triplea.Constants;
import games.strategy.triplea.Properties;
import games.strategy.triplea.TripleAUnit;
import games.strategy.triplea.attatchments.PlayerAttachment;
import games.strategy.triplea.attatchments.RulesAttachment;
import games.strategy.triplea.attatchments.TerritoryAttachment;
import games.strategy.triplea.attatchments.UnitAttachment;
import games.strategy.triplea.delegate.dataObjects.PlaceableUnits;
import games.strategy.triplea.delegate.remote.IAbstractPlaceDelegate;
import games.strategy.triplea.formatter.MyFormatter;
import games.strategy.triplea.player.ITripleaPlayer;
import games.strategy.util.CompositeMatch;
import games.strategy.util.CompositeMatchAnd;
import games.strategy.util.IntegerMap;
import games.strategy.util.Match;
import games.strategy.util.Tuple;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 
 * Logic for placing units.
 * <p>
 * 
 * @author Sean Bridges
 * @version 1.0
 * 
 *          Known limitations.
 * 
 *          Doesn't take into account limits on number of factories that can be produced.
 * 
 *          Solved:
 *          The situation where one has two non original factories a,b each with
 *          production 2. If sea zone e neighbors a,b and sea zone f neighbors b. Then
 *          producing 2 in e was making it such that you cannot produce in f. The reason
 *          was that the production in e could be assigned to the factory in b, leaving no
 *          capacity to produce in f.
 *          A workaround was that if anyone ever accidently run into this situation
 *          then they could undo the production, produce in f first, and then produce in e.
 */
public abstract class AbstractPlaceDelegate extends BaseDelegate implements IAbstractPlaceDelegate
{
	// maps Territory-> Collection of units
	protected Map<Territory, Collection<Unit>> m_produced = new HashMap<Territory, Collection<Unit>>();
	// a list of CompositeChanges
	protected List<UndoablePlacement> m_placements = new ArrayList<UndoablePlacement>();
	
	public void initialize(final String name)
	{
		initialize(name, name);
	}
	
	@Override
	public void start(final IDelegateBridge aBridge)
	{
		super.start(aBridge);
	}
	
	/**
	 * Called before the delegate will stop running.
	 */
	@Override
	public void end()
	{
		super.end();
		final PlayerID player = m_bridge.getPlayerID();
		// clear all units not placed
		final Collection<Unit> units = player.getUnits().getUnits();
		final GameData data = getData();
		if (!Properties.getUnplacedUnitsLive(data) && !units.isEmpty())
		{
			m_bridge.getHistoryWriter().startEvent(MyFormatter.unitsToTextNoOwner(units) + " were produced but were not placed");
			m_bridge.getHistoryWriter().setRenderingData(units);
			final Change change = ChangeFactory.removeUnits(player, units);
			m_bridge.addChange(change);
		}
		// reset ourselves for next turn
		m_produced = new HashMap<Territory, Collection<Unit>>();
		m_placements.clear();
		// only for lhtr rules
		new AirThatCantLandUtil(m_bridge).removeAirThatCantLand(m_player, false);
	}
	
	@Override
	public Serializable saveState()
	{
		final PlaceExtendedDelegateState state = new PlaceExtendedDelegateState();
		state.superState = super.saveState();
		// add other variables to state here:
		state.m_produced = m_produced;
		state.m_placements = m_placements;
		return state;
	}
	
	@Override
	public void loadState(final Serializable state)
	{
		final PlaceExtendedDelegateState s = (PlaceExtendedDelegateState) state;
		super.loadState(s.superState);
		// load other variables from state here:
		m_produced = s.m_produced;
		m_placements = s.m_placements;
	}
	
	/**
	 * 
	 * @param t
	 *            territory of interest
	 * @return collection of units that are produced at territory t
	 */
	private Collection<Unit> getAlreadyProduced(final Territory t)
	{
		if (m_produced.containsKey(t))
			return m_produced.get(t);
		return new ArrayList<Unit>();
	}
	
	public int getPlacementsMade()
	{
		return m_placements.size();
	}
	
	void setProduced(final Map<Territory, Collection<Unit>> produced)
	{
		m_produced = produced;
	}
	
	protected final Map<Territory, Collection<Unit>> getProduced()
	{
		return m_produced;
	}
	
	// returns List<AbstractUndoableMove>
	public List<UndoablePlacement> getMovesMade()
	{
		return m_placements;
	}
	
	public String undoMove(final int moveIndex)
	{
		if (moveIndex < m_placements.size() && moveIndex >= 0)
		{
			final UndoablePlacement undoPlace = m_placements.get(moveIndex);
			undoPlace.undo(getData(), m_bridge);
			m_placements.remove(moveIndex);
			updateUndoablePlacementIndexes();
		}
		return null;
	}
	
	private void updateUndoablePlacementIndexes()
	{
		for (int i = 0; i < m_placements.size(); i++)
		{
			m_placements.get(i).setIndex(i);
		}
	}
	
	public String placeUnits(final Collection<Unit> units, final Territory at)
	{
		if (units == null || units.isEmpty())
		{
			return null;
		}
		final String error = isValidPlacement(units, at, m_player);
		if (error != null)
			return error;
		performPlace(new ArrayList<Unit>(units), at, m_player);
		return null;
	}
	
	public PlaceableUnits getPlaceableUnits(final Collection<Unit> units, final Territory to)
	{
		final String error = canProduce(to, units, m_player);
		if (error != null)
			return new PlaceableUnits(error);
		final Collection<Unit> placeableUnits = getUnitsToBePlaced(to, units, m_player);
		final int maxUnits = getMaxUnitsToBePlaced(units, to, m_player, true);
		return new PlaceableUnits(placeableUnits, maxUnits);
	}
	
	/**
	 * Subclasses can over ride this to change the way placements are made.
	 * 
	 * @return null if placement is valid
	 */
	protected String isValidPlacement(final Collection<Unit> units, final Territory at, final PlayerID player)
	{
		// do we hold enough units
		String error = playerHasEnoughUnits(units, at, player);
		if (error != null)
			return error;
		// can we produce that much
		error = canProduce(at, units, player);
		if (error != null)
			return error;
		// can we produce that much
		error = checkProduction(at, units, player);
		if (error != null)
			return error;
		// can we place it
		error = canUnitsBePlaced(at, units, player);
		if (error != null)
			return error;
		return null;
	}
	
	/**
	 * Make sure the player has enough in hand to place the units.
	 */
	String playerHasEnoughUnits(final Collection<Unit> units, final Territory at, final PlayerID player)
	{
		// make sure the player has enough units in hand to place
		if (!player.getUnits().getUnits().containsAll(units))
			return "Not enough units";
		return null;
	}
	
	private boolean canProduceFightersOnCarriers()
	{
		return games.strategy.triplea.Properties.getProduce_Fighters_On_Carriers(getData());
	}
	
	private boolean canProduceNewFightersOnOldCarriers()
	{
		return games.strategy.triplea.Properties.getProduce_New_Fighters_On_Old_Carriers(getData());
	}
	
	private boolean canMoveExistingFightersToNewCarriers()
	{
		return games.strategy.triplea.Properties.getMove_Existing_Fighters_To_New_Carriers(getData());
	}
	
	/**
	 * The rule is that new fighters can be produced on new carriers. This does
	 * not allow for fighters to be produced on old carriers.
	 */
	private String validateNewAirCanLandOnCarriers(final Territory to, final Collection<Unit> units)
	{
		final int cost = AirMovementValidator.carrierCost(units);
		int capacity = AirMovementValidator.carrierCapacity(units, to);
		capacity += AirMovementValidator.carrierCapacity(to.getUnits().getUnits(), to);
		if (cost > capacity)
			return "Not enough new carriers to land all the fighters";
		return null;
	}
	
	/**
	 * @param to
	 *            referring territory
	 * @param player
	 *            PlayerID
	 * @return whether there was an owned unit capable of producing, in this territory at the start of this phase/step
	 */
	public boolean wasOwnedUnitThatCanProduceUnitsOrIsFactoryInTerritoryAtStartOfStep(final Territory to, final PlayerID player)
	{
		final Collection<Unit> unitsAtStartOfTurnInTO = unitsAtStartOfStepInTerritory(to);
		return Match.countMatches(unitsAtStartOfTurnInTO, Matches.UnitIsOwnedAndIsFactoryOrCanProduceUnits(player)) > 0;
	}
	
	/**
	 * @param to
	 *            referring territory
	 * @return collection of units that were there at start of turn
	 */
	public Collection<Unit> unitsAtStartOfStepInTerritory(final Territory to)
	{
		if (to == null)
			return new ArrayList<Unit>();
		final Collection<Unit> unitsInTO = to.getUnits().getUnits();
		final Collection<Unit> unitsPlacedAlready = getAlreadyProduced(to);
		if (Matches.TerritoryIsWater.match(to))
		{
			for (final Territory current : getAllProducers(to, m_player, null))
			{
				unitsPlacedAlready.addAll(getAlreadyProduced(current));
			}
		}
		final Collection<Unit> unitsAtStartOfTurnInTO = new ArrayList<Unit>(unitsInTO);
		unitsAtStartOfTurnInTO.removeAll(unitsPlacedAlready);
		return unitsAtStartOfTurnInTO;
	}
	
	public Collection<Unit> unitsPlacedInTerritorySoFar(final Territory to)
	{
		if (to == null)
			return new ArrayList<Unit>();
		final Collection<Unit> unitsInTO = to.getUnits().getUnits();
		final Collection<Unit> unitsAtStartOfStep = unitsAtStartOfStepInTerritory(to);
		unitsInTO.removeAll(unitsAtStartOfStep);
		return unitsInTO;
	}
	
	/**
	 * @param to
	 *            referring territory
	 * @param units
	 *            units to place
	 * @param player
	 *            PlayerID
	 * @return an empty IntegerMap if you can't produce any constructions (will never return null)
	 */
	public IntegerMap<String> howManyOfEachConstructionCanPlace(final Territory to, final Collection<Unit> units, final PlayerID player)
	{
		if (units == null || units.isEmpty() || !Match.someMatch(units, Matches.UnitIsFactoryOrConstruction))
			return new IntegerMap<String>();
		final Collection<Unit> unitsInTO = to.getUnits().getUnits();
		final Collection<Unit> unitsPlacedAlready = getAlreadyProduced(to);
		if (Matches.TerritoryIsWater.match(to))
		{
			for (final Territory current : getAllProducers(to, m_player, units))
			{
				unitsPlacedAlready.addAll(getAlreadyProduced(current));
			}
		}
		final Collection<Unit> unitsAtStartOfTurnInTO = new ArrayList<Unit>(unitsInTO);
		unitsAtStartOfTurnInTO.removeAll(unitsPlacedAlready);
		// build an integer map of each unit we have in our list of held units, as well as integer maps for maximum units and units per turn
		final IntegerMap<String> unitMapHeld = new IntegerMap<String>();
		final IntegerMap<String> unitMapMaxType = new IntegerMap<String>();
		final IntegerMap<String> unitMapTypePerTurn = new IntegerMap<String>();
		final int maxFactory = games.strategy.triplea.Properties.getFactoriesPerCountry(getData());
		final Iterator<Unit> unitHeldIter = Match.getMatches(units, Matches.UnitIsFactoryOrConstruction).iterator();
		final TerritoryAttachment terrAttachment = TerritoryAttachment.get(to);
		int toProduction = 0;
		if (terrAttachment != null)
			toProduction = terrAttachment.getProduction();
		while (unitHeldIter.hasNext())
		{
			final Unit currentUnit = unitHeldIter.next();
			final UnitAttachment ua = UnitAttachment.get(currentUnit.getUnitType());
			// account for any unit placement restrictions by territory
			if (isUnitPlacementRestrictions())
			{
				final String[] terrs = ua.getUnitPlacementRestrictions();
				final Collection<Territory> listedTerrs = getListedTerritories(terrs);
				if (listedTerrs.contains(to))
					continue;
				if (ua.getCanOnlyBePlacedInTerritoryValuedAtX() != -1 && ua.getCanOnlyBePlacedInTerritoryValuedAtX() > toProduction)
					continue;
				if (UnitWhichRequiresUnitsHasRequiredUnits(to).invert().match(currentUnit))
					continue;
			}
			// remove any units that require other units to be consumed on creation (veqryn)
			if (Matches.UnitConsumesUnitsOnCreation.match(currentUnit) && Matches.UnitWhichConsumesUnitsHasRequiredUnits(unitsAtStartOfTurnInTO, to).invert().match(currentUnit))
				continue;
			if (Matches.UnitIsFactory.match(currentUnit) && !ua.getIsConstruction())
			{
				unitMapHeld.add("factory", 1);
				unitMapMaxType.put("factory", maxFactory);
				unitMapTypePerTurn.put("factory", 1);
			}
			else
			{
				unitMapHeld.add(ua.getConstructionType(), 1);
				unitMapMaxType.put(ua.getConstructionType(), ua.getMaxConstructionsPerTypePerTerr());
				unitMapTypePerTurn.put(ua.getConstructionType(), ua.getConstructionsPerTerrPerTypePerTurn());
			}
		}
		final boolean moreWithoutFactory = games.strategy.triplea.Properties.getMoreConstructionsWithoutFactory(getData());
		final boolean moreWithFactory = games.strategy.triplea.Properties.getMoreConstructionsWithFactory(getData());
		final boolean unlimitedConstructions = games.strategy.triplea.Properties.getUnlimitedConstructions(getData());
		final boolean wasFactoryThereAtStart = wasOwnedUnitThatCanProduceUnitsOrIsFactoryInTerritoryAtStartOfStep(to, player);
		// build an integer map of each construction unit in the territory
		final IntegerMap<String> unitMapTO = new IntegerMap<String>();
		if (Match.someMatch(unitsInTO, Matches.UnitIsFactoryOrConstruction))
		{
			for (final Unit currentUnit : Match.getMatches(unitsInTO, Matches.UnitIsFactoryOrConstruction))
			{
				final UnitAttachment ua = UnitAttachment.get(currentUnit.getUnitType());
				if (Matches.UnitIsFactory.match(currentUnit) && !ua.getIsConstruction())
					unitMapTO.add("factory", 1);
				else
					unitMapTO.add(ua.getConstructionType(), 1);
			}
			// account for units already in the territory, based on max
			final Iterator<String> mapString = unitMapHeld.keySet().iterator();
			while (mapString.hasNext())
			{
				final String constructionType = mapString.next();
				int unitMax = unitMapMaxType.getInt(constructionType);
				if (wasFactoryThereAtStart && !constructionType.equals("factory") && !constructionType.endsWith("structure"))
					unitMax = Math.max(Math.max(unitMax, (moreWithFactory ? toProduction : 0)), (unlimitedConstructions ? 10000 : 0));
				if (!wasFactoryThereAtStart && !constructionType.equals("factory") && !constructionType.endsWith("structure"))
					unitMax = Math.max(Math.max(unitMax, (moreWithoutFactory ? toProduction : 0)), (unlimitedConstructions ? 10000 : 0));
				unitMapHeld.put(constructionType, Math.max(0, Math.min(unitMax - unitMapTO.getInt(constructionType), unitMapHeld.getInt(constructionType))));
			}
		}
		// deal with already placed units
		final Iterator<Unit> unitAlready = Match.getMatches(unitsPlacedAlready, Matches.UnitIsFactoryOrConstruction).iterator();
		while (unitAlready.hasNext())
		{
			final Unit currentUnit = unitAlready.next();
			final UnitAttachment ua = UnitAttachment.get(currentUnit.getUnitType());
			unitMapTypePerTurn.add(ua.getConstructionType(), -1);
		}
		// modify this list based on how many we can place per turn
		final IntegerMap<String> unitsAllowed = new IntegerMap<String>();
		final Iterator<String> mapString2 = unitMapHeld.keySet().iterator();
		while (mapString2.hasNext())
		{
			final String constructionType = mapString2.next();
			final int unitAllowed = Math.max(0, Math.min(unitMapTypePerTurn.getInt(constructionType), unitMapHeld.getInt(constructionType)));
			if (unitAllowed > 0)
				unitsAllowed.put(constructionType, unitAllowed);
		}
		// return our integer map
		return unitsAllowed;
	}
	
	public int howManyOfConstructionUnit(final Unit unit, final IntegerMap<String> constructionsMap)
	{
		final UnitAttachment ua = UnitAttachment.get(unit.getUnitType());
		if (!ua.getIsFactory()
					&& (!ua.getIsConstruction() || ua.getConstructionsPerTerrPerTypePerTurn() < 1 || ua.getMaxConstructionsPerTypePerTerr() < 1 || constructionsMap.getInt(ua.getConstructionType()) == 0))
			return 0;
		if (ua.getIsFactory() && !ua.getIsConstruction())
			return constructionsMap.getInt("factory");
		return constructionsMap.getInt(ua.getConstructionType());
	}
	
	/**
	 * @param to
	 *            referring territory
	 * @return whether the territory contains one of the required combos of units
	 *         (also when unit is Sea and an adjacent land territory has one of the required combos of units)
	 */
	public Match<Unit> UnitWhichRequiresUnitsHasRequiredUnits(final Territory to)
	{
		return new Match<Unit>()
		{
			@Override
			public boolean match(final Unit unitWhichRequiresUnits)
			{
				if (!Matches.UnitRequiresUnitsOnCreation.match(unitWhichRequiresUnits))
					return true;
				final Collection<Unit> unitsAtStartOfTurnInTO = unitsAtStartOfStepInTerritory(to);
				if (Matches.UnitWhichRequiresUnitsHasRequiredUnitsInList(unitsAtStartOfTurnInTO).match(unitWhichRequiresUnits))
					return true;
				if (Matches.UnitIsSea.match(unitWhichRequiresUnits))
				{
					final List<Territory> neighbors = new ArrayList<Territory>(to.getData().getMap().getNeighbors(to, Matches.TerritoryIsLand));
					for (final Territory current : neighbors)
					{
						final Collection<Unit> unitsInCurrent = current.getUnits().getUnits();
						final Collection<Unit> unitsPlacedAlreadyInCurrent = getAlreadyProduced(current);
						final Collection<Unit> unitsAtStartOfTurnInCurrent = new ArrayList<Unit>(unitsInCurrent);
						unitsAtStartOfTurnInCurrent.removeAll(unitsPlacedAlreadyInCurrent);
						// unitsAtStartOfTurnInCurrent.retainAll(Match.getMatches(unitsAtStartOfTurnInCurrent, Matches.UnitIsLand)); //this is debatable, depends what map makers want
						if (Matches.UnitWhichRequiresUnitsHasRequiredUnitsInList(unitsAtStartOfTurnInCurrent).match(unitWhichRequiresUnits))
							return true;
					}
				}
				return false;
			}
		};
	}
	
	public String canUnitsBePlaced(final Territory to, final Collection<Unit> units, final PlayerID player)
	{
		final Collection<Unit> allowedUnits = getUnitsToBePlaced(to, units, player);
		if (allowedUnits == null || !allowedUnits.containsAll(units))
		{
			return "Cannot place these units in " + to.getName();
		}
		final IntegerMap<String> constructionMap = howManyOfEachConstructionCanPlace(to, units, player);
		for (final Unit currentUnit : Match.getMatches(units, Matches.UnitIsFactoryOrConstruction))
		{
			final UnitAttachment ua = UnitAttachment.get(currentUnit.getUnitType());
			if (ua.getIsFactory() && !ua.getIsConstruction())
				constructionMap.add("factory", -1);
			else
				constructionMap.add(ua.getConstructionType(), -1);
		}
		if (!constructionMap.isPositive())
			return "Too many constructions in " + to.getName();
		final List<Territory> capitalsListOwned = new ArrayList<Territory>(TerritoryAttachment.getAllCurrentlyOwnedCapitals(player, getData()));
		if (!capitalsListOwned.contains(to) && isPlacementInCapitalRestricted(player))
			return "Cannot place these units outside of the capital";
		if (to.isWater())
		{
			final String canLand = validateNewAirCanLandOnCarriers(to, units);
			if (canLand != null)
				return canLand;
		}
		else
		{
			// make sure we own the territory
			if (!to.getOwner().equals(player))
				return "You don't own " + to.getName();
			// make sure all units are land
			if (!Match.allMatch(units, Matches.UnitIsNotSea))
				return "Cant place sea units on land";
		}
		// make sure we can place consuming units
		if (!canWeConsumeUnits(units, to, false, null))
			return "Not Enough Units To Upgrade or Be Consumed";
		// now check for stacking limits
		final Collection<UnitType> typesAlreadyChecked = new ArrayList<UnitType>();
		for (final Unit currentUnit : units)
		{
			final UnitType ut = currentUnit.getType();
			if (typesAlreadyChecked.contains(ut))
				continue;
			typesAlreadyChecked.add(ut);
			final int maxForThisType = UnitAttachment.getMaximumNumberOfThisUnitTypeToReachStackingLimit("placementLimit", ut, to, player, getData());
			if (Match.countMatches(units, Matches.unitIsOfType(ut)) > maxForThisType)
				return "UnitType " + ut.getName() + " is over stacking limit of " + maxForThisType;
		}
		if (!PlayerAttachment.getCanTheseUnitsMoveWithoutViolatingStackingLimit("placementLimit", units, to, player, getData()))
			return "Units Can Not Go Over Stacking Limit";
		// now return null (valid placement) if we have placement restrictions disabled in game options
		if (!isUnitPlacementRestrictions())
			return null;
		// account for any unit placement restrictions by territory
		for (final Unit currentUnit : units)
		{
			final UnitAttachment ua = UnitAttachment.get(currentUnit.getUnitType());
			final TerritoryAttachment ta = TerritoryAttachment.get(to);
			if (ua.getCanOnlyBePlacedInTerritoryValuedAtX() != -1 && ua.getCanOnlyBePlacedInTerritoryValuedAtX() > ta.getProduction())
				return "Cannot place these units in " + to.getName() + " due to Unit Placement Restrictions on Territory Value";
			final String[] terrs = ua.getUnitPlacementRestrictions();
			final Collection<Territory> listedTerrs = getListedTerritories(terrs);
			if (listedTerrs.contains(to))
				return "Cannot place these units in " + to.getName() + " due to Unit Placement Restrictions";
			if (UnitWhichRequiresUnitsHasRequiredUnits(to).invert().match(currentUnit))
				return "Cannot place these units in " + to.getName() + " as territory does not contain required units at start of turn";
			if (Matches.UnitCanOnlyPlaceInOriginalTerritories.match(currentUnit) && !Matches.TerritoryIsOriginallyOwnedBy(player).match(to))
				return "Cannot place these units in " + to.getName() + " as territory is not originally owned";
		}
		return null;
	}
	
	private Collection<Unit> getUnitsToBePlaced(final Territory to, final Collection<Unit> units, final PlayerID player)
	{
		if (to.isWater())
		{
			return getUnitsToBePlacedSea(to, units, player);
		}
		// if land
		return getUnitsToBePlacedLand(to, units, player);
	}
	
	protected Collection<Unit> getUnitsToBePlacedSea(final Territory to, final Collection<Unit> units, final PlayerID player)
	{
		final Collection<Unit> placeableUnits = new ArrayList<Unit>();
		final Collection<Unit> unitsAtStartOfTurnInTO = unitsAtStartOfStepInTerritory(to);
		// Land units wont do
		placeableUnits.addAll(Match.getMatches(units, Matches.UnitIsSea));
		final Territory producer = getProducer(to, player);
		final Collection<Unit> allProducedUnits = new ArrayList<Unit>(units);
		allProducedUnits.addAll(getAlreadyProduced(producer));
		// if can place new fighters on NEW CVs ---OR--- can place new fighters on OLD CVs
		if (((canProduceFightersOnCarriers() || AirThatCantLandUtil.isLHTRCarrierProduction(getData())) && Match.someMatch(allProducedUnits, Matches.UnitIsCarrier))
					|| ((canProduceNewFightersOnOldCarriers() || AirThatCantLandUtil.isLHTRCarrierProduction(getData())) && Match.someMatch(to.getUnits().getUnits(), Matches.UnitIsCarrier)))
		{
			final CompositeMatch<Unit> airThatCanLandOnCarrier = new CompositeMatchAnd<Unit>();
			airThatCanLandOnCarrier.add(Matches.UnitIsAir);
			airThatCanLandOnCarrier.add(Matches.UnitCanLandOnCarrier);
			placeableUnits.addAll(Match.getMatches(units, airThatCanLandOnCarrier));
		}
		if ((!isWW2V2() && !isUnitPlacementInEnemySeas()) && to.getUnits().someMatch(Matches.enemyUnit(player, getData())))
			return null;
		// remove any units that require other units to be consumed on creation (veqryn)
		if (Match.someMatch(placeableUnits, Matches.UnitConsumesUnitsOnCreation))
		{
			final Collection<Unit> unitsWhichConsume = Match.getMatches(placeableUnits, Matches.UnitConsumesUnitsOnCreation);
			for (final Unit unit : unitsWhichConsume)
			{
				if (Matches.UnitWhichConsumesUnitsHasRequiredUnits(unitsAtStartOfTurnInTO, to).invert().match(unit))
					placeableUnits.remove(unit);
			}
		}
		if (!isUnitPlacementRestrictions())
			return placeableUnits;
		final Collection<Unit> placeableUnits2 = new ArrayList<Unit>();
		for (final Unit currentUnit : placeableUnits)
		{
			final UnitAttachment ua = UnitAttachment.get(currentUnit.getUnitType());
			final TerritoryAttachment ta = TerritoryAttachment.get(to);
			if (ua.getCanOnlyBePlacedInTerritoryValuedAtX() != -1 && ua.getCanOnlyBePlacedInTerritoryValuedAtX() > ta.getProduction())
				continue;
			if (UnitWhichRequiresUnitsHasRequiredUnits(to).invert().match(currentUnit))
				continue;
			if (Matches.UnitCanOnlyPlaceInOriginalTerritories.match(currentUnit) && !Matches.TerritoryIsOriginallyOwnedBy(player).match(to))
				continue;
			// account for any unit placement restrictions by territory
			final String[] terrs = ua.getUnitPlacementRestrictions();
			final Collection<Territory> listedTerrs = getListedTerritories(terrs);
			if (!listedTerrs.contains(to))
				placeableUnits2.add(currentUnit);
		}
		return placeableUnits2;
	}
	
	protected Collection<Unit> getUnitsToBePlacedLand(final Territory to, final Collection<Unit> units, final PlayerID player)
	{
		final Collection<Unit> placeableUnits = new ArrayList<Unit>();
		final Collection<Unit> unitsAtStartOfTurnInTO = unitsAtStartOfStepInTerritory(to);
		final boolean wasFactoryThereAtStart = wasOwnedUnitThatCanProduceUnitsOrIsFactoryInTerritoryAtStartOfStep(to, player);
		if (wasFactoryThereAtStart || isPlayerAllowedToPlacementAnyTerritoryOwnedLand(player))
		{
			final CompositeMatch<Unit> groundUnits = new CompositeMatchAnd<Unit>(Matches.UnitIsLand, Matches.UnitIsNotFactoryOrConstruction); // we add factories and constructions later
			final CompositeMatch<Unit> airUnits = new CompositeMatchAnd<Unit>(Matches.UnitIsAir, Matches.UnitIsNotFactoryOrConstruction);
			placeableUnits.addAll(Match.getMatches(units, groundUnits));
			placeableUnits.addAll(Match.getMatches(units, airUnits));
		}
		if (Match.someMatch(units, Matches.UnitIsFactoryOrConstruction))
		{
			final IntegerMap<String> constructionsMap = howManyOfEachConstructionCanPlace(to, units, player);
			final Collection<Unit> skipUnit = new ArrayList<Unit>();
			for (final Unit currentUnit : Match.getMatches(units, Matches.UnitIsFactoryOrConstruction))
			{
				final int maxUnits = howManyOfConstructionUnit(currentUnit, constructionsMap);
				if (maxUnits > 0)
				{
					// we are doing this because we could have multiple unitTypes with the same constructionType, so we have to be able to place the max placement by constructionType of each unitType
					if (skipUnit.contains(currentUnit))
						continue;
					placeableUnits.addAll(Match.getNMatches(units, maxUnits, Matches.unitIsOfType(currentUnit.getType())));
					skipUnit.addAll(Match.getMatches(units, Matches.unitIsOfType(currentUnit.getType())));
				}
			}
		}
		// remove any units that require other units to be consumed on creation (veqryn)
		if (Match.someMatch(placeableUnits, Matches.UnitConsumesUnitsOnCreation))
		{
			final Collection<Unit> unitsWhichConsume = Match.getMatches(placeableUnits, Matches.UnitConsumesUnitsOnCreation);
			for (final Unit unit : unitsWhichConsume)
			{
				if (Matches.UnitWhichConsumesUnitsHasRequiredUnits(unitsAtStartOfTurnInTO, to).invert().match(unit))
					placeableUnits.remove(unit);
			}
		}
		// now check stacking limits
		final Collection<Unit> placeableUnits2 = new ArrayList<Unit>();
		final Collection<UnitType> typesAlreadyChecked = new ArrayList<UnitType>();
		for (final Unit currentUnit : placeableUnits)
		{
			final UnitType ut = currentUnit.getType();
			if (typesAlreadyChecked.contains(ut))
				continue;
			typesAlreadyChecked.add(ut);
			placeableUnits2.addAll(Match.getNMatches(placeableUnits, UnitAttachment.getMaximumNumberOfThisUnitTypeToReachStackingLimit("placementLimit", ut, to, player, getData()),
						Matches.unitIsOfType(ut)));
		}
		if (!isUnitPlacementRestrictions())
			return placeableUnits2;
		final Collection<Unit> placeableUnits3 = new ArrayList<Unit>();
		for (final Unit currentUnit : placeableUnits2)
		{
			final UnitAttachment ua = UnitAttachment.get(currentUnit.getUnitType());
			final TerritoryAttachment ta = TerritoryAttachment.get(to);
			if (ua.getCanOnlyBePlacedInTerritoryValuedAtX() != -1 && ua.getCanOnlyBePlacedInTerritoryValuedAtX() > ta.getProduction())
				continue;
			if (UnitWhichRequiresUnitsHasRequiredUnits(to).invert().match(currentUnit))
				continue;
			if (Matches.UnitCanOnlyPlaceInOriginalTerritories.match(currentUnit) && !Matches.TerritoryIsOriginallyOwnedBy(player).match(to))
				continue;
			// account for any unit placement restrictions by territory
			final String[] terrs = ua.getUnitPlacementRestrictions();
			final Collection<Territory> listedTerrs = getListedTerritories(terrs);
			if (!listedTerrs.contains(to))
				placeableUnits3.add(currentUnit);
		}
		return placeableUnits3;
	}
	
	protected boolean canWeConsumeUnits(final Collection<Unit> units, final Territory to, final boolean actuallyDoIt, final CompositeChange change)
	{
		boolean weCanConsume = true;
		final Collection<Unit> unitsInTO = to.getUnits().getUnits();
		final Collection<Unit> unitsPlacedAlready = getAlreadyProduced(to);
		if (Matches.TerritoryIsWater.match(to))
		{
			for (final Territory current : getAllProducers(to, m_player, units))
			{
				unitsPlacedAlready.addAll(getAlreadyProduced(current));
			}
		}
		final Collection<Unit> unitsAtStartOfTurnInTO = new ArrayList<Unit>(unitsInTO);
		unitsAtStartOfTurnInTO.removeAll(unitsPlacedAlready);
		final Collection<Unit> removedUnits = new ArrayList<Unit>();
		final Collection<Unit> unitsWhichConsume = Match.getMatches(units, Matches.UnitConsumesUnitsOnCreation);
		for (final Unit unit : unitsWhichConsume)
		{
			if (Matches.UnitWhichConsumesUnitsHasRequiredUnits(unitsAtStartOfTurnInTO, to).invert().match(unit))
				weCanConsume = false;
			if (!weCanConsume)
				break;
			// remove units which are now consumed, then test the rest of the consuming units on the diminishing pile of units which were in the territory at start of turn
			final UnitAttachment ua = UnitAttachment.get(unit.getType());
			final IntegerMap<UnitType> requiredUnitsMap = ua.getConsumesUnits();
			final Collection<UnitType> requiredUnits = requiredUnitsMap.keySet();
			for (final UnitType ut : requiredUnits)
			{
				final int requiredNumber = requiredUnitsMap.getInt(ut);
				final Match<Unit> unitIsOwnedByAndOfTypeAndNotDamaged = new CompositeMatchAnd<Unit>(Matches.unitIsOwnedBy(unit.getOwner()), Matches.unitIsOfType(ut), Matches.UnitHasSomeUnitDamage()
							.invert(), Matches.UnitIsNotDamaged, Matches.UnitIsDisabled().invert(), Matches.unitIsInTerritoryThatHasTerritoryDamage(to).invert());
				final Collection<Unit> unitsBeingRemoved = Match.getNMatches(unitsAtStartOfTurnInTO, requiredNumber, unitIsOwnedByAndOfTypeAndNotDamaged);
				unitsAtStartOfTurnInTO.removeAll(unitsBeingRemoved);
				// if we should actually do it, not just test, then add to bridge
				if (actuallyDoIt && change != null)
				{
					final Change remove = ChangeFactory.removeUnits(to, unitsBeingRemoved);
					change.add(remove);
					removedUnits.addAll(unitsBeingRemoved);
				}
			}
		}
		if (weCanConsume && actuallyDoIt && change != null && !change.isEmpty())
		{
			// m_bridge.addChange(change);
			m_bridge.getHistoryWriter().startEvent("Units in " + to.getName() + " being upgraded or consumed: " + MyFormatter.unitsToTextNoOwner(removedUnits));
			m_bridge.getHistoryWriter().setRenderingData(removedUnits);
		}
		return weCanConsume;
	}
	
	/**
	 * Returns -1 if can place unlimited units
	 */
	protected int getMaxUnitsToBePlaced(final Collection<Unit> units, final Territory to, final PlayerID player, final boolean countSwitchedProductionToNeighbors)
	{
		final List<Territory> producers = getAllProducers(to, player, units);
		if (producers.isEmpty())
			return 0;
		int production = 0;
		Collections.sort(producers, getBestProducerComparator(to, units, player));
		final Collection<Territory> notUsableAsOtherProducers = new ArrayList<Territory>();
		notUsableAsOtherProducers.addAll(producers);
		for (final Territory producerTerritory : producers)
		{
			final int prodT = getMaxUnitsToBePlacedFrom(producerTerritory, units, to, player, countSwitchedProductionToNeighbors, notUsableAsOtherProducers);
			if (prodT == -1)
				return -1;
			production += prodT;
		}
		return production;
	}
	
	/**
	 * Returns -1 if can place unlimited units
	 */
	protected int getMaxUnitsToBePlacedFrom(final Territory producer, final Collection<Unit> units, final Territory to, final PlayerID player, final boolean countSwitchedProductionToNeighbors,
				final Collection<Territory> notUsableAsOtherProducers)
	{
		// if its an original factory then unlimited production
		final TerritoryAttachment ta = TerritoryAttachment.get(producer);
		final Collection<Unit> factoryUnits = producer.getUnits().getMatches(Matches.UnitIsOwnedAndIsFactoryOrCanProduceUnits(player));
		// boolean placementRestrictedByFactory = isPlacementRestrictedByFactory();
		final boolean unitPlacementPerTerritoryRestricted = isUnitPlacementPerTerritoryRestricted();
		final boolean originalFactory = ta.getOriginalFactory();
		final boolean playerIsOriginalOwner = factoryUnits.size() > 0 ? m_player.equals(getOriginalFactoryOwner(producer)) : false;
		final RulesAttachment ra = (RulesAttachment) player.getAttachment(Constants.RULES_ATTACHMENT_NAME);
		final Collection<Unit> alreadProducedUnits = getAlreadyProduced(producer);
		final int unitCountAlreadyProduced = alreadProducedUnits.size();
		if (originalFactory && playerIsOriginalOwner) // && !placementRestrictedByFactory && !unitPlacementPerTerritoryRestricted
		{
			if (ra != null && ra.getMaxPlacePerTerritory() != -1)
			{
				return Math.max(0, ra.getMaxPlacePerTerritory() - unitCountAlreadyProduced);
			}
			return -1;
		}
		// Restricts based on the STARTING number of units in a territory (otherwise it is infinite placement)
		if (unitPlacementPerTerritoryRestricted)
		{
			if (ra != null && ra.getPlacementPerTerritory() > 0)
			{
				final int allowedPlacement = ra.getPlacementPerTerritory();
				final int ownedUnitsInTerritory = Match.countMatches(to.getUnits().getUnits(), Matches.unitIsOwnedBy(player));
				if (ownedUnitsInTerritory >= allowedPlacement)
					return 0;
				if (ra.getMaxPlacePerTerritory() == -1)
					return -1;
				return Math.max(0, ra.getMaxPlacePerTerritory() - unitCountAlreadyProduced);
			}
		}
		// a factory can produce the same number of units as the number of PUs the territory generates each turn (or not, if it has canProduceXUnits)
		int production = 0;
		// int territoryValue = getProduction(producer);
		final int maxConstructions = howManyOfEachConstructionCanPlace(to, units, player).totalValues();
		final boolean wasFactoryThereAtStart = wasOwnedUnitThatCanProduceUnitsOrIsFactoryInTerritoryAtStartOfStep(producer, player);
		// If there's NO factory, allow placement of the factory
		if (!wasFactoryThereAtStart)
		{
			if (ra != null && ra.getMaxPlacePerTerritory() > 0)
				return Math.max(0, Math.min(maxConstructions, ra.getMaxPlacePerTerritory() - unitCountAlreadyProduced));
			return Math.max(0, maxConstructions);
		}
		// getHowMuchCanUnitProduce accounts for IncreasedFactoryProduction, but does not account for maxConstructions
		production = TripleAUnit.getProductionPotentialOfTerritory(unitsAtStartOfStepInTerritory(producer), producer, player, getData(), true, true);
		// increase the production by the number of constructions allowed
		if (maxConstructions > 0)
			production += maxConstructions;
		// return 0 if less than 0
		if (production < 0)
			return 0;
		production += Match.countMatches(alreadProducedUnits, Matches.UnitIsFactoryOrConstruction);
		
		// Now we check if units we have already produced here could be produced by a different producer
		int unitCountHaveToAndHaveBeenBeProducedHere = unitCountAlreadyProduced;
		if (countSwitchedProductionToNeighbors && unitCountAlreadyProduced > 0)
		{
			int productionThatCanBeTakenOver = 0;
			// try to find a placement move (to an adjacent sea zone) that can be taken over by some other territory factory
			for (final UndoablePlacement placementMove : m_placements)
			{
				if (placementMove.getProducerTerritory().equals(producer))
				{
					final Territory placeTerritory = placementMove.getPlaceTerritory();
					if (!placeTerritory.isWater())
						continue;
					// find other producers for this placement move to the same water territory
					final List<Territory> newPotentialOtherProducers = getAllProducers(placeTerritory, player, units);
					Collections.sort(newPotentialOtherProducers, getBestProducerComparator(placeTerritory, units, player));
					newPotentialOtherProducers.removeAll(notUsableAsOtherProducers);
					for (final Territory potentialOtherProducer : newPotentialOtherProducers)
					{
						final int potential = getMaxUnitsToBePlacedFrom(potentialOtherProducer, unitsPlacedInTerritorySoFar(placeTerritory), placeTerritory, player, false, null);
						if (potential == -1)
							return -1;
						productionThatCanBeTakenOver += potential;
					}
					notUsableAsOtherProducers.addAll(newPotentialOtherProducers);
					if (productionThatCanBeTakenOver >= unitCountAlreadyProduced)
						break;
				}
			}
			unitCountHaveToAndHaveBeenBeProducedHere = Math.max(0, unitCountAlreadyProduced - productionThatCanBeTakenOver);
		}
		if (ra != null && ra.getMaxPlacePerTerritory() > 0)
			return Math.max(0, Math.min(production - unitCountHaveToAndHaveBeenBeProducedHere, ra.getMaxPlacePerTerritory() - unitCountHaveToAndHaveBeenBeProducedHere));
		return Math.max(0, production - unitCountHaveToAndHaveBeenBeProducedHere);
	}
	
	/**
	 * @return gets the production of the territory
	 */
	protected int getProduction(final Territory territory)
	{
		final TerritoryAttachment ta = TerritoryAttachment.get(territory);
		if (ta != null)
			return ta.getProduction();
		return 0;
	}
	
	/**
	 * Test whether or not the territory has the factory resources to support
	 * the placement. AlreadyProduced maps territory->units already produced
	 * this turn by that territory.
	 */
	protected String canProduce(final Territory to, final Collection<Unit> units, final PlayerID player)
	{
		final Collection<Territory> producers = getAllProducers(to, player, units);
		// the only reason it could be empty is if its water and no
		// territories adjacent have factories
		if (producers.isEmpty())
			return "No factory adjacent to " + to.getName();
		if (producers.size() == 1)
			return canProduce(producers.iterator().next(), to, units, player);
		final Collection<Territory> failingProducers = new ArrayList<Territory>();
		String error = "";
		for (final Territory producer : producers)
		{
			final String errorP = canProduce(producer, to, units, player);
			if (errorP != null)
			{
				failingProducers.add(producer);
				error += ", " + errorP;
			}
		}
		if (producers.size() == failingProducers.size())
			return "Adjacent factories to " + to.getName() + " cannot produce, due to: \n " + error.replaceFirst(", ", "");
		return null;
	}
	
	protected String canProduce(final Territory producer, final Territory to, final Collection<Unit> units, final PlayerID player)
	{
		// make sure the territory wasnt conquered this turn
		if (wasConquered(producer) && !isPlacementAllowedInCapturedTerritory(player))
			return producer.getName() + " was conquered this turn and cannot produce till next turn";
		if (isPlayerAllowedToPlacementAnyTerritoryOwnedLand(player))
			return null;
		// make sure there is a factory
		if (!producer.getUnits().someMatch(Matches.UnitIsFactoryOrCanProduceUnits))
		{
			// check to see if we are producing a factory
			if (Match.someMatch(units, Matches.UnitIsFactory))
				return null;
			if (Match.someMatch(units, Matches.UnitIsConstruction))
			{
				if (howManyOfEachConstructionCanPlace(to, units, player).totalValues() > 0) // No error, Construction to place
					return null;
				return "No more Constructions Allowed in " + producer.getName();
			}
			return "No Factory in " + producer.getName();
		}
		// check we havent just put a factory there (should we be checking producer?)
		if (Match.someMatch(getAlreadyProduced(to), Matches.UnitIsFactoryOrCanProduceUnits))
		{
			if (Match.someMatch(units, Matches.UnitIsConstruction) && howManyOfEachConstructionCanPlace(to, units, player).totalValues() > 0) // you can still place a Construction
				return null;
			return "Factory in " + producer.getName() + " cant produce until 1 turn after it is created";
		}
		if (to.isWater() && (!isWW2V2() && !isUnitPlacementInEnemySeas()) && to.getUnits().someMatch(Matches.enemyUnit(player, getData())))
			return "Cannot place sea units with enemy naval units";
		return null;
	}
	
	/**
	 * Test whether or not the territory has the factory resources to support
	 * the placement. AlreadyProduced maps territory->units already produced
	 * this turn by that territory.
	 */
	protected String checkProduction(final Territory to, final Collection<Unit> units, final PlayerID player)
	{
		final Territory producer = getProducer(to, player);
		if (producer == null)
			return "No factory adjacent to " + to.getName();
		// if its an original factory then unlimited production
		final TerritoryAttachment ta = TerritoryAttachment.get(producer);
		// WW2V2, you cant place factories in territories with no production
		if (isWW2V2() && ta.getProduction() == 0 && !Match.someMatch(units, Matches.UnitIsConstruction))
		{
			return "Cannot place factory, that territory cant produce any units";
		}
		final int maxUnitsToBePlaced = getMaxUnitsToBePlaced(units, to, player, true);
		if ((maxUnitsToBePlaced != -1) && (maxUnitsToBePlaced < units.size()))
			return "Cannot place " + units.size() + " more units in " + producer.getName();
		return null;
	}
	
	protected boolean isWW2V2()
	{
		return games.strategy.triplea.Properties.getWW2V2(getData());
	}
	
	private boolean isWW2V3()
	{
		return games.strategy.triplea.Properties.getWW2V3(getData());
	}
	
	private boolean isMultipleAAPerTerritory()
	{
		return games.strategy.triplea.Properties.getMultipleAAPerTerritory(getData());
	}
	
	protected boolean isUnitPlacementInEnemySeas()
	{
		return games.strategy.triplea.Properties.getUnitPlacementInEnemySeas(getData());
	}
	
	private boolean wasConquered(final Territory t)
	{
		final BattleTracker tracker = DelegateFinder.battleDelegate(getData()).getBattleTracker();
		return tracker.wasConquered(t);
	}
	
	private boolean isPlaceInAnyTerritory()
	{
		return games.strategy.triplea.Properties.getPlaceInAnyTerritory(getData());
	}
	
	private boolean isUnitPlacementPerTerritoryRestricted()
	{
		return games.strategy.triplea.Properties.getUnitPlacementPerTerritoryRestricted(getData());
	}
	
	private boolean isUnitPlacementRestrictions()
	{
		return games.strategy.triplea.Properties.getUnitPlacementRestrictions(getData());
	}
	
	/**
	 * Returns the better producer of the two territories, either of which can
	 * be null.
	 */
	private Territory getBetterProducer(final Territory t1, final Territory t2, final PlayerID player)
	{
		// anything is better than nothing
		if (t1 == null)
			return t2;
		if (t2 == null)
			return t1;
		// conquered cant produce
		if (wasConquered(t1))
			return t2;
		if (wasConquered(t2))
			return t1;
		// original factories are good
		final TerritoryAttachment t1a = TerritoryAttachment.get(t1);
		if (t1a.getOriginalFactory() && isOriginalOwner(t1, player))
			return t1;
		final TerritoryAttachment t2a = TerritoryAttachment.get(t2);
		if (t2a.getOriginalFactory() && isOriginalOwner(t2, player))
			return t2;
		// which can produce the most
		if (getProduction(t1) - getAlreadyProduced(t1).size() > getProduction(t2) - getAlreadyProduced(t2).size())
			return t1;
		return t2;
	}
	
	private boolean isOriginalOwner(final Territory t, final PlayerID id)
	{
		final OriginalOwnerTracker tracker = DelegateFinder.battleDelegate(getData()).getOriginalOwnerTracker();
		return tracker.getOriginalOwner(t).equals(id);
	}
	
	/**
	 * Returns the territory that would do the producing if units are to be
	 * placed in a given territory. Returns null if no suitable territory could
	 * be found.
	 */
	@Deprecated
	private Territory getProducer(final Territory to, final PlayerID player)
	{
		// TODO: eliminate use of this, in favor of getAllProducers
		// if not water then must produce in that territory
		if (!to.isWater())
			return to;
		Territory neighborFactory = null;
		for (final Territory current : getAllProducers(to, player, null))
		{
			neighborFactory = getBetterProducer(current, neighborFactory, player);
		}
		return neighborFactory;
	}
	
	/**
	 * Returns the territories that would do the producing if units are to be placed in
	 * a given territory. Returns an empty list if no suitable territory could be found.
	 */
	private List<Territory> getAllProducers(final Territory to, final PlayerID player, final Collection<Unit> unitsToPlace)
	{
		final List<Territory> producers = new ArrayList<Territory>();
		// if not water then must produce in that territory
		if (!to.isWater())
		{
			producers.add(to);
			return producers;
		}
		for (final Territory current : getData().getMap().getNeighbors(to))
		{
			if (current.getOwner().equals(m_player))
			{
				final Collection<Unit> unitsInTO = current.getUnits().getUnits();
				final Collection<Unit> unitsPlacedAlready = getAlreadyProduced(current);
				final Collection<Unit> unitsAtStartOfTurnInTO = new ArrayList<Unit>(unitsInTO);
				unitsAtStartOfTurnInTO.removeAll(unitsPlacedAlready);
				unitsAtStartOfTurnInTO.retainAll(Match.getMatches(unitsAtStartOfTurnInTO, Matches.unitIsOwnedBy(player)));
				if (Match.someMatch(unitsAtStartOfTurnInTO, Matches.UnitIsOwnedAndIsFactoryOrCanProduceUnits(player)) || isPlayerAllowedToPlacementAnySeaZoneByOwnedLand(player))
				{
					producers.add(current);
				}
			}
		}
		return producers;
	}
	
	private Comparator<Territory> getBestProducerComparator(final Territory to, final Collection<Unit> units, final PlayerID player)
	{
		return new Comparator<Territory>()
		{
			public int compare(final Territory t1, final Territory t2)
			{
				if (t1.equals(t2))
					return 0;
				final int left1 = getMaxUnitsToBePlacedFrom(t1, units, to, player, false, null);
				final int left2 = getMaxUnitsToBePlacedFrom(t2, units, to, player, false, null);
				if (left1 == left2)
					return 0;
				if (left1 == -1)
					return -1;
				if (left2 == -1)
					return 1;
				if (left1 > left2)
					return -1;
				return 1;
			}
		};
	}
	
	/**
	 * There must be a factory in the territory or an illegal state exception
	 * will be thrown. return value may be null.
	 */
	private PlayerID getOriginalFactoryOwner(final Territory territory)
	{
		final Collection<Unit> factoryUnits = territory.getUnits().getMatches(Matches.UnitIsFactoryOrCanProduceUnits);
		if (factoryUnits.size() == 0)
			throw new IllegalStateException("No factory in territory:" + territory);
		final Iterator<Unit> iter = factoryUnits.iterator();
		final GameData data = getData();
		while (iter.hasNext())
		{
			final Unit factory2 = iter.next();
			if (m_player.equals(DelegateFinder.battleDelegate(data).getOriginalOwnerTracker().getOriginalOwner(factory2)))
				return DelegateFinder.battleDelegate(data).getOriginalOwnerTracker().getOriginalOwner(factory2);
		}
		final Unit factory = factoryUnits.iterator().next();
		return DelegateFinder.battleDelegate(data).getOriginalOwnerTracker().getOriginalOwner(factory);
	}
	
	private void performPlace(final Collection<Unit> units, final Territory at, final PlayerID player)
	{
		final List<Territory> producers = getAllProducers(at, player, units);
		Collections.sort(producers, getBestProducerComparator(at, units, player));
		final Collection<Unit> unitsLeftToPlace = new ArrayList<Unit>(units);
		for (final Territory producer : producers)
		{
			// don't forget that -1 = infinite
			int maxPlaceable = getMaxUnitsToBePlaced(unitsLeftToPlace, producer, player, true);
			if (maxPlaceable == 0)
				continue;
			final int tooManyToPlace = unitsLeftToPlace.size() - maxPlaceable;
			if (maxPlaceable != -1 && tooManyToPlace > 0)
			{
				final Collection<Unit> placedUnits = new ArrayList<Unit>();
				for (final Unit unit : unitsLeftToPlace)
				{
					placedUnits.add(unit);
					if (maxPlaceable > 1)
						--maxPlaceable;
					else
						break;
				}
				performPlaceFrom(producer, placedUnits, at, player);
				unitsLeftToPlace.removeAll(placedUnits);
			}
			else
			{
				final int maxForThisProducer = getMaxUnitsToBePlacedFrom(producer, unitsLeftToPlace, at, player, false, null);
				if (maxForThisProducer != -1 && unitsLeftToPlace.size() > maxForThisProducer)
				{
					freePlacementCapacity(producer, unitsLeftToPlace.size(), unitsLeftToPlace, at, player);
				}
				performPlaceFrom(producer, unitsLeftToPlace, at, player);
				break;
			}
		}
	}
	
	/**
	 * 
	 * @param producer
	 *            territory that produces the new units
	 * @param placeableUnits
	 *            the new units
	 * @param at
	 *            territory where the new units get placed
	 */
	private void performPlaceFrom(final Territory producer, final Collection<Unit> placeableUnits, final Territory at, final PlayerID player)
	{
		final CompositeChange change = new CompositeChange();
		// make sure we can place consuming units
		final boolean didIt = canWeConsumeUnits(placeableUnits, at, true, change);
		if (!didIt)
			throw new IllegalStateException("Something wrong with consuming/upgrading units");
		final Collection<Unit> factoryAndInfrastructure = Match.getMatches(placeableUnits, Matches.UnitIsFactoryOrIsInfrastructure);
		change.add(DelegateFinder.battleDelegate(getData()).getOriginalOwnerTracker().addOriginalOwnerChange(factoryAndInfrastructure, player));
		// can we move planes to land there
		final String movedAirTranscriptTextForHistory = moveAirOntoNewCarriers(at, placeableUnits, player, change);
		
		final Change remove = ChangeFactory.removeUnits(player, placeableUnits);
		final Change place = ChangeFactory.addUnits(at, placeableUnits);
		change.add(remove);
		change.add(place);
		final UndoablePlacement current_placement = new UndoablePlacement(m_player, change, producer, at, placeableUnits);
		m_placements.add(current_placement);
		updateUndoablePlacementIndexes();
		final String transcriptText = MyFormatter.unitsToTextNoOwner(placeableUnits) + " placed in " + at.getName();
		m_bridge.getHistoryWriter().startEvent(transcriptText);
		m_bridge.getHistoryWriter().setRenderingData(current_placement.getDescriptionObject());
		if (movedAirTranscriptTextForHistory != null)
			m_bridge.getHistoryWriter().addChildToEvent(movedAirTranscriptTextForHistory);
		m_bridge.addChange(change);
		updateProducedMap(producer, placeableUnits);
	}
	
	private void updateProducedMap(final Territory producer, final Collection<Unit> additionallyProducedUnits)
	{
		final Collection<Unit> newProducedUnits = getAlreadyProduced(producer);
		newProducedUnits.addAll(additionallyProducedUnits);
		m_produced.put(producer, newProducedUnits);
	}
	
	/**
	 * frees the requested amount of capacity for the given producer by trying to hand over already made placements to other territories.
	 * This only works if one of the placements is done for another territory, more specific for a sea zone.
	 * If such placements exists it will be tried to let them be done by other adjacent territories.
	 * 
	 * @param producer
	 *            territory that needs more placement capacity
	 * @param freeSize
	 *            amount of capacity that is requested
	 */
	private void freePlacementCapacity(final Territory producer, final int freeSize, final Collection<Unit> unitsLeftToPlace, final Territory at, final PlayerID player)
	{
		int foundSpaceTotal = 0;
		final Collection<UndoablePlacement> redoPlacements = new ArrayList<UndoablePlacement>(); // placements of the producer that could be redone by other territories
		final HashMap<Territory, Integer> redoPlacementsCount = new HashMap<Territory, Integer>(); // territories the producer produced for (but not itself) and the amount of units it produced
		// find map place territory -> possible free space for producer
		for (final UndoablePlacement placement : m_placements)
		{
			// find placement move of producer that can be taken over
			if (placement.getProducerTerritory().equals(producer))
			{
				final Territory placeTerritory = placement.getPlaceTerritory();
				if (placeTerritory.isWater() && !placeTerritory.equals(producer))
				{
					// found placement move of producer that can be taken over
					// remember move and amount of placements in that territory
					redoPlacements.add(placement);
					final Integer integer = redoPlacementsCount.get(placeTerritory);
					if (integer == null)
						redoPlacementsCount.put(placeTerritory, placement.getUnits().size());
					else
						redoPlacementsCount.put(placeTerritory, integer + placement.getUnits().size());
				}
			}
		}
		// let other producers take over placements of producer
		// remember placement move and new territory if a placement has to be split up
		final Collection<Tuple<UndoablePlacement, Territory>> splitPlacements = new ArrayList<Tuple<UndoablePlacement, Territory>>();
		for (final Territory placeTerritory : redoPlacementsCount.keySet())
		{
			// find other producers that could produce for the placeTerritory
			final List<Territory> potentialNewProducers = getAllProducers(placeTerritory, player, unitsLeftToPlace);
			Collections.sort(potentialNewProducers, getBestProducerComparator(placeTerritory, unitsLeftToPlace, player));
			potentialNewProducers.remove(producer);
			// we can just free a certain amount or still need a certain amount of space
			final int maxSpaceToBeFree = Math.min(redoPlacementsCount.get(placeTerritory), freeSize - foundSpaceTotal);
			int spaceAlreadyFree = 0; // space that got free this on this placeTerritory
			for (final Territory potentialNewProducerTerritory : potentialNewProducers)
			{
				final int leftToPlace = getMaxUnitsToBePlacedFrom(potentialNewProducerTerritory, unitsPlacedInTerritorySoFar(placeTerritory), placeTerritory, player, false, null);
				if (leftToPlace == -1)
					throw new IllegalStateException("We should not be trying to free placement up in a producer that has infinite placement!");
				// find placements of the producer the potentialNewProducerTerritory can take over
				for (final UndoablePlacement placement : redoPlacements)
				{
					final Collection<Unit> placedUnits = placement.getUnits();
					final int placementSize = placedUnits.size();
					if (placementSize <= leftToPlace)
					{
						// potentialNewProducerTerritory can take over complete production
						placement.setProducerTerritory(potentialNewProducerTerritory);
						getAlreadyProduced(producer).removeAll(placedUnits);
						updateProducedMap(potentialNewProducerTerritory, placedUnits);
						spaceAlreadyFree += placementSize;
					}
					else
					{
						// potentialNewProducerTerritory can take over ONLY parts of the production
						// remember placement and potentialNewProducerTerritory but try to avoid splitting a placement
						splitPlacements.add(new Tuple<UndoablePlacement, Territory>(placement, potentialNewProducerTerritory));
					}
					if (spaceAlreadyFree >= maxSpaceToBeFree)
						break;
				}
				if (spaceAlreadyFree >= maxSpaceToBeFree)
					break;
			}
			foundSpaceTotal += spaceAlreadyFree;
			if (foundSpaceTotal >= freeSize)
				break;
		}
		if (foundSpaceTotal < freeSize)
		{
			// we need to split some placement moves
			for (final Tuple<UndoablePlacement, Territory> tuple : splitPlacements)
			{
				final UndoablePlacement placement = tuple.getFirst();
				final Territory newProducer = tuple.getSecond();
				int leftToPlace = getMaxUnitsToBePlacedFrom(newProducer, unitsLeftToPlace, at, player, false, null);
				foundSpaceTotal += leftToPlace;
				// divide set of units that get placed
				final Collection<Unit> unitsForOldProducer = new ArrayList<Unit>(placement.getUnits());
				final Collection<Unit> unitsForNewProducer = new ArrayList<Unit>();
				for (final Unit unit : unitsForOldProducer)
				{
					if (leftToPlace == 0)
						break;
					unitsForNewProducer.add(unit);
					--leftToPlace;
				}
				unitsForOldProducer.removeAll(unitsForNewProducer);
				// split move, by undo and creating two new ones
				undoMove(placement.getIndex());
				performPlaceFrom(newProducer, unitsForNewProducer, placement.getPlaceTerritory(), player);
				if (!unitsForNewProducer.isEmpty())
					performPlaceFrom(producer, unitsForOldProducer, placement.getPlaceTerritory(), player);
			}
		}
	}
	
	private ITripleaPlayer getRemotePlayer()
	{
		return (ITripleaPlayer) m_bridge.getRemote();
	}
	
	// TODO Here's the spot for special air placement rules
	private String moveAirOntoNewCarriers(final Territory territory, final Collection<Unit> units, final PlayerID player, final CompositeChange placeChange)
	{
		// not water, dont bother
		if (!territory.isWater())
			return null;
		// not enabled
		// if (!canProduceFightersOnCarriers())
		if (!canMoveExistingFightersToNewCarriers() || AirThatCantLandUtil.isLHTRCarrierProduction(getData()))
			return null;
		if (Match.noneMatch(units, Matches.UnitIsCarrier))
			return null;
		// do we have any spare carrier capacity
		int capacity = AirMovementValidator.carrierCapacity(units, territory);
		// subtract fighters that have already been produced with this carrier
		// this turn.
		capacity -= AirMovementValidator.carrierCost(units);
		if (capacity <= 0)
			return null;
		final Collection<Territory> neighbors = getData().getMap().getNeighbors(territory, 1);
		final Iterator<Territory> iter = neighbors.iterator();
		final CompositeMatch<Unit> ownedFighters = new CompositeMatchAnd<Unit>(Matches.UnitCanLandOnCarrier, Matches.unitIsOwnedBy(player));
		while (iter.hasNext())
		{
			final Territory neighbor = iter.next();
			if (neighbor.isWater())
				continue;
			// check to see if we have a factory, only fighters from territories
			// that could
			// have produced the carrier can move there
			if (!neighbor.getUnits().someMatch(Matches.UnitIsFactoryOrCanProduceUnits))
				continue;
			// are there some fighers there that can be moved?
			if (!neighbor.getUnits().someMatch(ownedFighters))
				continue;
			if (wasConquered(neighbor))
				continue;
			if (Match.someMatch(getAlreadyProduced(neighbor), Matches.UnitIsFactoryOrCanProduceUnits))
				continue;
			final List<Unit> fighters = neighbor.getUnits().getMatches(ownedFighters);
			while (fighters.size() > 0 && AirMovementValidator.carrierCost(fighters) > capacity)
			{
				fighters.remove(0);
			}
			if (fighters.size() == 0)
				continue;
			final Collection<Unit> movedFighters = getRemotePlayer().getNumberOfFightersToMoveToNewCarrier(fighters, neighbor);
			final Change change = ChangeFactory.moveUnits(neighbor, territory, movedFighters);
			placeChange.add(change);
			final String transcriptText = MyFormatter.unitsToTextNoOwner(movedFighters) + " moved from " + neighbor.getName() + " to " + territory.getName();
			// only allow 1 movement
			// technically only the territory that produced the
			// carrier should be able to move fighters to the new
			// territory
			return transcriptText;
		}
		return null;
	}
	
	/**
	 * Get what air units must move before the end of the players turn
	 * 
	 * @return a list of Territories with air units that must move
	 */
	public Collection<Territory> getTerritoriesWhereAirCantLand()
	{
		return new AirThatCantLandUtil(m_bridge).getTerritoriesWhereAirCantLand(m_player);
	}
	
	private boolean isPlayerAllowedToPlacementAnyTerritoryOwnedLand(final PlayerID player)
	{
		if (isPlaceInAnyTerritory())
		{
			final RulesAttachment ra = (RulesAttachment) player.getAttachment(Constants.RULES_ATTACHMENT_NAME);
			if (ra != null && ra.getPlacementAnyTerritory())
			{
				return true;
			}
		}
		return false;
	}
	
	private boolean isPlayerAllowedToPlacementAnySeaZoneByOwnedLand(final PlayerID player)
	{
		if (isPlaceInAnyTerritory())
		{
			final RulesAttachment ra = (RulesAttachment) player.getAttachment(Constants.RULES_ATTACHMENT_NAME);
			if (ra != null && ra.getPlacementAnySeaZone())
			{
				return true;
			}
		}
		return false;
	}
	
	private boolean isPlacementAllowedInCapturedTerritory(final PlayerID player)
	{
		final RulesAttachment ra = (RulesAttachment) player.getAttachment(Constants.RULES_ATTACHMENT_NAME);
		if (ra != null && ra.getPlacementCapturedTerritory())
		{
			return true;
		}
		return false;
	}
	
	private boolean isPlacementInCapitalRestricted(final PlayerID player)
	{
		final RulesAttachment ra = (RulesAttachment) player.getAttachment(Constants.RULES_ATTACHMENT_NAME);
		if (ra != null && ra.getPlacementInCapitalRestricted())
		{
			return true;
		}
		return false;
	}
	
	/*
	 * @see games.strategy.engine.delegate.IDelegate#getRemoteType()
	 */
	@Override
	public Class<? extends IRemote> getRemoteType()
	{
		return IAbstractPlaceDelegate.class;
	}
	
	private Collection<Territory> getListedTerritories(final String[] list)
	{
		final List<Territory> rVal = new ArrayList<Territory>();
		if (list == null)
			return rVal;
		for (final String name : list)
		{
			// Validate all territories exist
			final Territory territory = getData().getMap().getTerritory(name);
			if (territory == null)
				throw new IllegalStateException("Rules & Conditions: No territory called:" + name);
			rVal.add(territory);
		}
		return rVal;
	}
	
	/* never used methods?
	private boolean isSBRAffectsUnitProduction()
	{
	    return games.strategy.triplea.Properties.getSBRAffectsUnitProduction(m_data);
	}

	private boolean isDamageFromBombingDoneToUnitsInsteadOfTerritories()
	{
	    return games.strategy.triplea.Properties.getDamageFromBombingDoneToUnitsInsteadOfTerritories(m_data);
	}

	private boolean isPlacementRestrictedByFactory()
	{
	    return games.strategy.triplea.Properties.getPlacementRestrictedByFactory(m_data);
	}

	private boolean isIncreasedFactoryProduction(PlayerID player)
	{
	    TechAttachment ta = (TechAttachment) player.getAttachment(Constants.TECH_ATTACHMENT_NAME);
	    if(ta == null)
	        return false;
	    return ta.hasIncreasedFactoryProduction();
	}

	private boolean hasConstruction(Territory to)
	{
	    return to.getUnits().someMatch(Matches.UnitIsConstruction);
	}
	*/
}


@SuppressWarnings("serial")
class PlaceExtendedDelegateState implements Serializable
{
	Serializable superState;
	// add other variables here:
	public Map<Territory, Collection<Unit>> m_produced;
	public List<UndoablePlacement> m_placements;
}
