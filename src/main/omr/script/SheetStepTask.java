//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                    S h e e t S t e p T a s k                                   //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//  Copyright © Hervé Bitteur and others 2000-2016. All rights reserved.
//  This software is released under the GNU General Public License.
//  Goto http://kenai.com/projects/audiveris to report bugs or suggestions.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package omr.script;

import omr.log.LogUtil;
import static omr.script.ScriptTask.logger;

import omr.sheet.Sheet;

import omr.step.Step;
import omr.step.StepException;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * Class {@code SheetStepTask} performs a step on a single sheet.
 *
 * @author Hervé Bitteur
 */
@XmlRootElement(name = "sheet-step")
@XmlAccessorType(XmlAccessType.NONE)
public class SheetStepTask
        extends SheetTask
{
    //~ Instance fields ----------------------------------------------------------------------------

    /** The step launched */
    @XmlAttribute(name = "name")
    private Step step;

    //~ Constructors -------------------------------------------------------------------------------
    /**
     * Create a task to apply a given step to the related sheet.
     *
     * @param sheet the sheet to process
     * @param step  the step to apply
     */
    public SheetStepTask (Sheet sheet,
                          Step step)
    {
        super(sheet);
        this.step = step;
    }

    /** No-arg constructor needed by JAXB. */
    private SheetStepTask ()
    {
    }

    //~ Methods ------------------------------------------------------------------------------------
    //------//
    // core //
    //------//
    @Override
    public void core (final Sheet sheet)
            throws StepException
    {
        try {
            LogUtil.start(sheet.getStub());
            sheet.getStub().reachStep(step);
            logger.info("End of sheet step {}", step);
            sheet.getStub().getBook().getScript().addTask(this);
        } finally {
            LogUtil.stopStub();
        }
    }

    //-----------//
    // internals //
    //-----------//
    @Override
    protected String internals ()
    {
        StringBuilder sb = new StringBuilder(super.internals());
        sb.append(" sheet-step ").append(step);

        return sb.toString();
    }
}
