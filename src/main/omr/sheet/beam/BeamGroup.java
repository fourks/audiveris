//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                        B e a m G r o u p                                       //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//  Copyright © Herve Bitteur and others 2000-2016. All rights reserved.
//  This software is released under the GNU General Public License.
//  Goto http://kenai.com/projects/audiveris to report bugs or suggestions.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package omr.sheet.beam;

import omr.constant.Constant;
import omr.constant.ConstantSet;

import omr.glyph.Glyph;

import omr.math.GeoUtil;
import omr.math.LineUtil;
import omr.math.Rational;

import omr.sheet.Scale;
import omr.sheet.Staff;
import omr.sheet.rhythm.Measure;
import omr.sheet.rhythm.MeasureStack;
import omr.sheet.rhythm.Voice;

import omr.sig.SIGraph;
import omr.sig.inter.AbstractBeamInter;
import omr.sig.inter.AbstractChordInter;
import omr.sig.inter.AbstractNoteInter;
import omr.sig.inter.HeadChordInter;
import omr.sig.inter.Inter;
import omr.sig.inter.StemInter;
import omr.sig.relation.BeamHeadRelation;
import omr.sig.relation.BeamStemRelation;
import omr.sig.relation.HeadStemRelation;
import omr.sig.relation.NoExclusion;
import omr.sig.relation.Relation;
import omr.sig.relation.StemAlignmentRelation;

import omr.util.Navigable;
import omr.util.Vip;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlIDREF;
import javax.xml.bind.annotation.XmlList;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * Class {@code BeamGroup} represents a group of related beams.
 * <p>
 * NOTA: Beams in a BeamGroup are in no particular order.
 *
 * @author Hervé Bitteur
 */
@XmlAccessorType(XmlAccessType.NONE)
@XmlRootElement(name = "beam-group")
public class BeamGroup
        implements Vip
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Constants constants = new Constants();

    private static final Logger logger = LoggerFactory.getLogger(
            BeamGroup.class);

    //~ Instance fields ----------------------------------------------------------------------------
    //
    // Persistent data
    //----------------
    //
    /** Id for debug mainly, unique within measure stack. */
    @XmlAttribute
    private final int id;

    /** Set of contained beams. */
    @XmlList
    @XmlIDREF
    @XmlElement
    private final HashSet<AbstractBeamInter> beams = new HashSet<AbstractBeamInter>();

    /** Indicates a beam group that is linked to more than one staff. */
    @XmlElement(name = "multi-staff")
    private Boolean multiStaff;

    // Transient data
    //---------------
    //
    /** (Debug) flag this object as VIP. */
    private boolean vip;

    /** Containing measure. */
    @Navigable(false)
    private Measure measure;

    /** Same voice for all chords in this beam group. */
    private Voice voice;

    //~ Constructors -------------------------------------------------------------------------------
    /**
     * Creates a new instance of BeamGroup.
     *
     * @param measure the containing measure
     */
    public BeamGroup (Measure measure)
    {
        this.measure = measure;

        measure.addBeamGroup(this);
        id = measure.getBeamGroups().size();

        logger.debug("{} Created {}", measure, this);
    }

    /**
     * No-arg constructor meant for JAXB.
     */
    private BeamGroup ()
    {
        this.id = 0;
    }

    //~ Methods ------------------------------------------------------------------------------------
    //----------//
    // populate //
    //----------//
    /**
     * Populate all the BeamGroup instances for a given measure stack.
     *
     * @param stack the containing measure stack
     */
    public static void populate (MeasureStack stack)
    {
        for (Measure measure : stack.getMeasures()) {
            // Retrieve beams in this measure
            Set<AbstractBeamInter> beams = new HashSet<AbstractBeamInter>();

            for (AbstractChordInter chord : measure.getHeadChords()) {
                beams.addAll(chord.getBeams());
            }

            // Build beam groups for this measure stack
            for (AbstractBeamInter beam : beams) {
                if (beam.getGroup() == null) {
                    BeamGroup group = new BeamGroup(measure);
                    assignGroup(group, beam);
                    logger.debug("{}", group);
                }
            }

            // In case something goes wrong, use an upper limit to loop
            int loopNb = constants.maxSplitLoops.getValue();

            while (checkBeamGroups(measure)) {
                if (--loopNb < 0) {
                    logger.warn("Loop detected in BeamGroup split in {}", measure);

                    break;
                }
            }

            // Detect groups that are linked to more than one staff
            for (BeamGroup group : measure.getBeamGroups()) {
                group.countStaves();
                logger.debug("   {}", group);
            }
        }
    }

    //---------//
    // addBeam //
    //---------//
    /**
     * Include a beam as part of this group.
     *
     * @param beam the beam to include
     */
    public void addBeam (AbstractBeamInter beam)
    {
        if (!beams.add(beam)) {
            logger.warn("{} already in {}", beam, this);
        }

        if (beam.isVip()) {
            setVip(true);
        }

        if (isVip() || logger.isDebugEnabled()) {
            logger.info("{} Added {} to {}", measure, beam, this);
        }
    }

    //-------------//
    // afterReload //
    //-------------//
    public void afterReload (Measure measure)
    {
        try {
            this.measure = measure;

            for (AbstractBeamInter beam : beams) {
                beam.setGroup(this);
            }
        } catch (Exception ex) {
            logger.warn("Error in " + getClass() + " afterReload() " + ex, ex);
        }
    }

    //-------------//
    // assignVoice //
    //-------------//
    /**
     * Just assign a voice to this beam group.
     *
     * @param voice the voice to assign
     */
    public void assignVoice (Voice voice)
    {
        this.voice = voice;
    }

    //--------------------//
    // computeTimeOffsets //
    //--------------------//
    /**
     * Compute time offsets for all chords of this beam group, assuming the first chord
     * of the group already has its time offset assigned.
     */
    public void computeTimeOffsets ()
    {
        AbstractChordInter prevChord = null;

        for (AbstractChordInter chord : getChords()) {
            if (prevChord != null) {
                try {
                    // Here we must check for interleaved rest
                    AbstractNoteInter rest = measure.lookupRest(prevChord, chord);

                    if (rest != null) {
                        rest.getChord().setTimeOffset(prevChord.getEndTime());
                        chord.setTimeOffset(rest.getChord().getEndTime());
                    } else {
                        chord.setTimeOffset(prevChord.getEndTime());
                    }
                } catch (Exception ex) {
                    logger.warn("{} Cannot compute chord time based on previous chord", chord);
                }
            } else if (chord.getTimeOffset() == null) {
                logger.warn("{} Computing beam group times with first chord not set", chord);
            }

            prevChord = chord;
        }
    }

    //----------//
    // getBeams //
    //----------//
    /**
     * Report the beams that are part of this group.
     *
     * @return the collection of contained beams
     */
    public Set<AbstractBeamInter> getBeams ()
    {
        return beams;
    }

    //-------------//
    // getDuration //
    //-------------//
    /**
     * Report the total duration of the sequence of chords within this group.
     * Beware, there may be rests inserted within beam-grouped notes.
     *
     * @return the total group duration, perhaps null
     */
    public Rational getDuration ()
    {
        final AbstractChordInter first = getFirstChord();
        final AbstractChordInter last = getLastChord();
        Rational duration = last.getTimeOffset().minus(first.getTimeOffset()).plus(
                last.getDuration());

        return duration;
    }

    //---------------//
    // getFirstChord //
    //---------------//
    /**
     * Report the first chord on the left.
     *
     * @return the first chord
     */
    public AbstractChordInter getFirstChord ()
    {
        List<AbstractChordInter> chords = getChords();

        if (!chords.isEmpty()) {
            return chords.get(0);
        } else {
            return null;
        }
    }

    //-------//
    // getId //
    //-------//
    /**
     * Report the group id (unique within the measure, starting from 1).
     *
     * @return the group id
     */
    public int getId ()
    {
        return id;
    }

    //--------------//
    // getLastChord //
    //--------------//
    /**
     * Report the last chord on the right.
     *
     * @return the last chord
     */
    public AbstractChordInter getLastChord ()
    {
        List<AbstractChordInter> chords = getChords();

        if (!chords.isEmpty()) {
            return chords.get(chords.size() - 1);
        } else {
            return null;
        }
    }

    //----------//
    // getVoice //
    //----------//
    public Voice getVoice ()
    {
        return voice;
    }

    //--------------//
    // isMultiStaff //
    //--------------//
    /**
     * @return the multiStaff
     */
    public boolean isMultiStaff ()
    {
        return (multiStaff != null) && multiStaff;
    }

    //-------//
    // isVip //
    //-------//
    @Override
    public boolean isVip ()
    {
        return vip;
    }

    //------------//
    // removeBeam //
    //------------//
    /**
     * Remove a beam from this group (in order to assign the beam to another group).
     *
     * @param beam the beam to remove
     */
    public void removeBeam (AbstractBeamInter beam)
    {
        if (!beams.remove(beam)) {
            logger.warn(beam + " not found in " + this);
        }
    }

    //-------------//
    // resetRhythm //
    //-------------//
    public void resetTiming ()
    {
        voice = null;
    }

    //--------//
    // setVip //
    //--------//
    @Override
    public void setVip (boolean vip)
    {
        this.vip = vip;
    }

    //----------//
    // setVoice //
    //----------//
    /**
     * Assign a voice to this beam group, and to the related entities.
     *
     * @param voice the voice to assign
     */
    public void setVoice (Voice voice)
    {
        // Already done?
        if (this.voice == null) {
            this.voice = voice;

            // Formard this information to the beamed chords
            // Including the interleaved rests if any
            AbstractChordInter prevChord = null;

            for (AbstractChordInter chord : getChords()) {
                if (prevChord != null) {
                    // Here we must check for interleaved rest
                    AbstractNoteInter rest = measure.lookupRest(prevChord, chord);

                    if (rest != null) {
                        rest.getChord().setVoice(voice);
                    }
                }

                chord.setVoice(voice);
                prevChord = chord;
            }
        } else if (voice == null) {
            this.voice = null;
        } else if (!this.voice.equals(voice)) {
            logger.warn("Reassigning voice from " + this.voice + " to " + voice + " in " + this);
        }
    }

    //----------//
    // toString //
    //----------//
    @Override
    public String toString ()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("{BeamGroup#").append(id).append(" beams[");

        if (beams != null) {
            for (AbstractBeamInter beam : beams) {
                sb.append(beam).append(" ");
            }
        }

        sb.append("]").append("}");

        return sb.toString();
    }

    //----------------//
    // determineGroup //
    //----------------//
    /**
     * Recursively determine BeamGroup for the provided beam, as well as all other beams
     * connected within the same group.
     *
     * @param beam    the beam seed
     * @param measure the containing measure
     */
    private static void assignGroup (BeamGroup group,
                                     AbstractBeamInter beam)
    {
        group.addBeam(beam);
        beam.setGroup(group);

        for (AbstractChordInter chord : beam.getChords()) {
            for (AbstractBeamInter b : chord.getBeams()) {
                if (b.getGroup() == null) {
                    assignGroup(group, b);
                }
            }
        }
    }

    //
    //    //-----------------//
    //    // checkBeamGroups //
    //    //-----------------//
    //    /**
    //     * Check all the BeamGroup instances of the given measure, to find the first split
    //     * if any to perform.
    //     *
    //     * @param measure the given measure
    //     * @return the first split parameters, or null if everything is OK
    //     */
    //    private static boolean checkBeamGroups (Measure measure)
    //    {
    //        for (BeamGroup group : measure.getBeamGroups()) {
    //            AbstractChordInter alienChord = group.checkForSplit();
    //
    //            if (alienChord != null) {
    //                group.split(alienChord);
    //
    //                return true;
    //            }
    //        }
    //
    //        return false;
    //    }
    //
    //-----------------//
    // checkBeamGroups //
    //-----------------//
    /**
     * Check all the BeamGroup instances of the given measure, to find the first
     * split if any to perform.
     *
     * @param measure the given measure
     * @return the first split parameters, or null if everything is OK
     */
    private static boolean checkBeamGroups (Measure measure)
    {
        for (BeamGroup group : measure.getBeamGroups()) {
            AbstractChordInter alienChord = group.checkForSplit();

            if (alienChord != null) {
                group.split(alienChord);

                return true;
            }
        }

        return false;
    }

    //---------------//
    // checkForSplit //
    //---------------//
    /**
     * Run a consistency check on the group, and detect when a group has to be split.
     *
     * @return the detected alien chord, or null if no split is needed
     */
    private AbstractChordInter checkForSplit ()
    {
        final Scale scale = measure.getPart().getSystem().getSheet().getScale();
        final double maxChordDy = constants.maxChordDy.getValue();

        // Make sure all chords are part of the same group
        // We check the vertical distance between any chord and the beams above or below the chord.
        for (AbstractChordInter chord : getChords()) {
            final Rectangle chordBox = chord.getBounds();
            final Point tail = chord.getTailLocation();

            // Get the collection of questionable beams WRT chord
            List<AbstractBeamInter> questionableBeams = new ArrayList<AbstractBeamInter>();

            for (AbstractBeamInter beam : beams) {
                // Skip beam hooks
                // Skip beams attached to this chord
                // Skip beams with no abscissa overlap WRT this chord
                if (!beam.isHook()
                    && !beam.getChords().contains(chord)
                    && (GeoUtil.xOverlap(beam.getBounds(), chordBox) > 0)) {
                    // Check vertical gap
                    int lineY = (int) Math.rint(LineUtil.yAtX(beam.getMedian(), tail.x));
                    int yOverlap = Math.min(lineY, chordBox.y + chordBox.height)
                                   - Math.max(lineY, chordBox.y);

                    if (yOverlap < 0) {
                        questionableBeams.add(beam);
                    }
                }
            }

            if (questionableBeams.isEmpty()) {
                continue; // No problem found around the chord at hand
            }

            // Sort these questionable beams vertically, at chord stem abscissa,
            // according to distance from chord tail.
            Collections.sort(
                    questionableBeams,
                    new Comparator<AbstractBeamInter>()
            {
                @Override
                public int compare (AbstractBeamInter b1,
                                    AbstractBeamInter b2)
                {
                    final double y1 = LineUtil.yAtX(b1.getMedian(), tail.x);
                    double tailDy1 = Math.abs(y1 - tail.y);
                    final double y2 = LineUtil.yAtX(b2.getMedian(), tail.x);
                    double tailDy2 = Math.abs(y2 - tail.y);

                    return Double.compare(tailDy1, tailDy2);
                }
            });

            AbstractBeamInter nearestBeam = questionableBeams.get(0);
            int lineY = (int) Math.rint(
                    LineUtil.yAtX(nearestBeam.getMedian(), tail.x));
            int tailDy = Math.abs(lineY - tail.y);
            double normedDy = scale.pixelsToFrac(tailDy);

            if (normedDy > maxChordDy) {
                logger.debug(
                        "Vertical gap between {} and {}, {} vs {}",
                        chord,
                        nearestBeam,
                        normedDy,
                        maxChordDy);

                // Split the beam group here
                return chord;
            }
        }

        return null; // everything is OK
    }

    //-------------//
    // countStaves //
    //-------------//
    /**
     * Check whether this group is linked to more than one staff.
     * If so, it is flagged as such.
     */
    private void countStaves ()
    {
        Set<Staff> staves = new HashSet<Staff>();

        for (AbstractBeamInter beam : beams) {
            SIGraph sig = beam.getSig();

            for (Relation rel : sig.getRelations(beam, BeamStemRelation.class)) {
                Inter stem = sig.getOppositeInter(beam, rel);
                Staff staff = stem.getStaff();

                if (staff != null) {
                    staves.add(staff);
                }
            }
        }

        if (staves.size() > 1) {
            multiStaff = true;
        }
    }

    //-----------//
    // getChords //
    //-----------//
    /**
     * Report the x-ordered collection of chords that are grouped by this beam group.
     *
     * @return the (perhaps empty) collection of 'beamed' chords.
     */
    private List<AbstractChordInter> getChords ()
    {
        List<AbstractChordInter> chords = new ArrayList<AbstractChordInter>();

        for (AbstractBeamInter beam : getBeams()) {
            for (AbstractChordInter chord : beam.getChords()) {
                if (!chords.contains(chord)) {
                    chords.add(chord);
                }
            }
        }

        Collections.sort(chords, AbstractChordInter.byAbscissa);

        return chords;
    }

    //-------//
    // split //
    //-------//
    private void split (AbstractChordInter alienChord)
    {
        new Splitter(alienChord).process();
    }

    //~ Inner Classes ------------------------------------------------------------------------------
    //-----------//
    // Constants //
    //-----------//
    private static final class Constants
            extends ConstantSet
    {
        //~ Instance fields ------------------------------------------------------------------------

        private final Constant.Integer maxSplitLoops = new Constant.Integer(
                "loops",
                10,
                "Maximum number of loops allowed for splitting beam groups");

        private final Scale.Fraction maxChordDy = new Scale.Fraction(
                0.5,
                "Maximum vertical gap between a chord and a beam");
    }

    //----------//
    // Splitter //
    //----------//
    /**
     * Utility class meant to perform a split on this group.
     * This group is shrunk, because some of its beams are moved to a new (alien) group.
     */
    private class Splitter
    {
        //~ Instance fields ------------------------------------------------------------------------

        /** Chord detected as belonging to a (new) alien group. */
        private final AbstractChordInter alienChord;

        /** Beams that belong to new alien group.
         * (Initially populated with all beams (except beam hooks) attached to alienChord) */
        private Set<AbstractBeamInter> alienBeams;

        /** The new alien group. */
        private BeamGroup alienGroup;

        /** The chord that embraces both (old) group and (new) alien group. */
        private HeadChordInter pivotChord;

        //~ Constructors ---------------------------------------------------------------------------
        /**
         * Create a splitter for this BeamGroup, triggered by alienChord
         *
         * @param alienChord a detected chord that should belong to a separate group
         */
        public Splitter (AbstractChordInter alienChord)
        {
            this.alienChord = alienChord;
        }

        //~ Methods --------------------------------------------------------------------------------
        //---------//
        // process //
        //---------//
        /**
         * Actually split the group in two, around the detected pivot chord.
         * <p>
         * Some beams of this group instance are moved to a new separate BeamGroup instance.
         * The two instances are articulated around a pivot chord, common to both groups.
         *
         */
        public void process ()
        {
            logger.debug("{} splitter on {}", BeamGroup.this, alienChord);

            // The new group on alienChord side
            alienGroup = createAlienGroup();

            // Detect the pivot chord shared by the two groups, and "split" it for both groups
            pivotChord = detectPivotChord();

            // Dispatch beams attached to pivotChord to their proper group
            dispatchPivotBeams();

            // Make sure all beams have been dispatched
            dispatchAllBeams();

            // Duplicate the chord between the two group
            splitChord();
        }

        //------------------//
        // createAlienGroup //
        //------------------//
        private BeamGroup createAlienGroup ()
        {
            alienGroup = new BeamGroup(measure);

            // Check all former beams: any beam linked to the detected alienChord should be
            // moved to the alienGroup.
            // (This cannot apply to beam hooks, they will be processed later)
            alienBeams = new HashSet<AbstractBeamInter>(alienChord.getBeams());

            // Now apply the move
            for (AbstractBeamInter beam : alienBeams) {
                beam.switchToGroup(alienGroup);
            }

            return alienGroup;
        }

        //------------------//
        // detectPivotChord //
        //------------------//
        /**
         * Look through the chords on the alienGroup to detect the one which is shared
         * by both this group and the alienGroup.
         *
         * @return the pivot chord found
         */
        private HeadChordInter detectPivotChord ()
        {
            List<AbstractChordInter> commons = getChords();
            commons.retainAll(alienGroup.getChords());

            // TODO: what if we have more than one common chord???
            return (HeadChordInter) commons.get(0);
        }

        //------------------//
        // dispatchAllBeams //
        //------------------//
        /**
         * Inspect all remaining beams in (old) group, and move to the (new) alien group
         * the ones which are connected to alien beams (except through the pivotChord).
         */
        private void dispatchAllBeams ()
        {
            List<AbstractBeamInter> pivotBeams = pivotChord.getBeams();
            AllLoop:
            for (AbstractBeamInter beam : new ArrayList<AbstractBeamInter>(beams)) {
                // If beam is attached to pivotChord, skip it
                if (pivotBeams.contains(beam)) {
                    continue;
                }

                // Check every beam chord, for touching an alienBeam
                for (AbstractChordInter chord : beam.getChords()) {
                    for (AbstractBeamInter b : chord.getBeams()) {
                        if (b.getGroup() == alienGroup) {
                            beam.switchToGroup(alienGroup);

                            continue AllLoop;
                        }
                    }
                }
            }
        }

        //--------------------//
        // dispatchPivotBeams //
        //--------------------//
        /**
         * Inspect the beams connected to pivotChord, and move to the (new) alien group
         * those which fall on the alienSide of the pivotChord.
         * This does not apply to beam hooks.
         */
        private void dispatchPivotBeams ()
        {
            // Select the tail beam of alienChord
            final AbstractBeamInter alienTailBeam = alienChord.getBeams().get(0);

            final List<AbstractBeamInter> pivotBeams = pivotChord.getBeams();
            Boolean onAlienSide = null;

            // Inspect the pivot beams, from tail to head
            for (int ib = 0; ib < pivotBeams.size(); ib++) {
                AbstractBeamInter b = pivotChord.getBeams().get(ib);

                if (b.isHook()) {
                    continue;
                }

                if (onAlienSide == null) {
                    onAlienSide = alienBeams.contains(b);
                }

                if (b == alienTailBeam) {
                    if (onAlienSide) {
                        // End of alien side
                        logger.debug("Alien end");

                        for (AbstractBeamInter ab : pivotBeams.subList(0, ib + 1)) {
                            if (!alienBeams.contains(ab)) {
                                ab.switchToGroup(alienGroup);
                            }
                        }
                    } else {
                        // Start of alien side
                        logger.debug("Alien start");

                        for (AbstractBeamInter ab : pivotBeams.subList(
                                ib,
                                pivotChord.getBeams().size())) {
                            if (!alienBeams.contains(ab)) {
                                ab.switchToGroup(alienGroup);
                            }
                        }
                    }

                    return;
                }
            }
        }

        //------------------//
        // extractShortStem //
        //------------------//
        private StemInter extractShortStem (AbstractChordInter chord,
                                            int yStop)
        {
            final int stemDir = chord.getStemDir();
            final StemInter rootStem = chord.getStem();
            final Glyph rootGlyph = rootStem.getGlyph();

            // Ordinate of head side of stem
            final int yStart = (stemDir > 0) ? rootGlyph.getTop()
                    : ((rootGlyph.getTop() + rootGlyph.getHeight()) - 1);

            return rootStem.extractSubStem(yStart, yStop);
        }

        //------------//
        // splitChord //
        //------------//
        /**
         * Split the chord which embraces the two beam groups.
         * <p>
         * At this point, each beam has been moved to its proper group, either this (old) group or
         * the (new) alienGroup. What remains to be done is to split the pivot chord between the
         * two groups.
         * <p>
         * The beam group (old or alien) located at tail of pivot chord reuses pivot chord & stem.
         * The other group (the one closer to heads) must use a shorter stem (and chord).
         * <p>
         * Also we have to void exclusion between any beam and the opposite (mirror) chord/stem
         */
        private void splitChord ()
        {
            logger.debug("splitChord: {}", pivotChord);

            final SIGraph sig = pivotChord.getSig();
            final List<AbstractBeamInter> pivotBeams = pivotChord.getBeams();
            final StemInter pivotStem = pivotChord.getStem();

            // Create a clone of pivotChord (heads are duplicated, but no stem or beams initially)
            HeadChordInter shortChord = pivotChord.duplicate(true);

            // The beams closer to tail will stay with pivotChord and its long stem
            // The beams closer to head (headBeams) will migrate to a new short chord & stem
            // For this, let's look at tail end of pivotChord
            final boolean aliensAtTail = alienBeams.contains(pivotBeams.get(0));
            final Set<AbstractBeamInter> headBeams = aliensAtTail ? beams : alienBeams;

            // Determine tail end for short stem, by walking on pivot from tail to head
            AbstractBeamInter firstHeadBeam = null;

            for (int i = 0; i < pivotBeams.size(); i++) {
                AbstractBeamInter beam = pivotBeams.get(i);

                if (headBeams.contains(beam)) {
                    firstHeadBeam = beam;

                    // Beam hooks to move?
                    for (AbstractBeamInter b : pivotBeams.subList(i + 1, pivotBeams.size())) {
                        if (b.isHook()) {
                            headBeams.add(b);
                        }
                    }

                    break;
                }
            }

            // Build shortStem
            Relation r = sig.getRelation(firstHeadBeam, pivotStem, BeamStemRelation.class);
            BeamStemRelation bsRel = (BeamStemRelation) r;
            int y = (int) Math.rint(bsRel.getExtensionPoint().getY());
            final StemInter shortStem = extractShortStem(pivotChord, y);
            shortChord.setStem(shortStem);
            sig.addEdge(shortStem, pivotStem, new StemAlignmentRelation());

            // Link mirrored heads to short stem
            for (Inter note : shortChord.getNotes()) {
                for (Relation hs : sig.getRelations(note.getMirror(), HeadStemRelation.class)) {
                    sig.addEdge(note, shortStem, hs.duplicate());
                }
            }

            // Update information related to headBeams
            for (AbstractBeamInter beam : headBeams) {
                // Avoid exclusion between head beam and pivotStem
                sig.addEdge(beam, pivotStem, new NoExclusion());

                // Move BeamStem relation from pivot to short
                Relation bs = sig.getRelation(beam, pivotStem, BeamStemRelation.class);
                sig.removeEdge(bs);
                sig.addEdge(beam, shortStem, bs);

                // Move BeamHead relation(s) from pivot to short
                Set<Relation> bhRels = sig.getRelations(beam, BeamHeadRelation.class);

                for (Relation bh : bhRels) {
                    Inter head = sig.getOppositeInter(beam, bh);

                    if (head.getEnsemble() == pivotChord) {
                        sig.removeEdge(bh);
                        sig.addEdge(beam, head.getMirror(), bh);
                    }
                }

                // Cut the links pivotChord <-> beam
                pivotBeams.remove(beam);
                beam.removeChord(pivotChord);

                // Link beam to shortChord
                shortChord.addBeam(beam);
                beam.addChord(shortChord);
            }

            measure.getStack().addInter(shortChord);
        }
    }
}