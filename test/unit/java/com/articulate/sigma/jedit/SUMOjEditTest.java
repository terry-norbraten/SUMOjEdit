package com.articulate.sigma.jedit;

import com.articulate.sigma.trans.SUMOtoTFAform;
import com.articulate.sigma.utils.FileUtil;
import errorlist.DefaultErrorSource;

import errorlist.ErrorSource;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 *
 * @author <a href="mailto:tdnorbra@nps.edu?subject=com.articulate.sigma.jedit.SUMOjEditTest">Terry Norbraten, NPS MOVES</a>
 */
public class SUMOjEditTest extends Assert {

    static String test;
    SUMOjEdit sje;

    @BeforeClass
    public static void beforeClass() {

        SUMOtoTFAform.initOnce();
        test = System.getenv("ONTOLOGYPORTAL_GIT") + "/SUMOjEdit/test/unit/java/resources/test";
    }

    @Before
    public void beforeTest() {

        sje = new SUMOjEdit();
        sje.kb = SUMOtoTFAform.kb;
        sje.fp = SUMOtoTFAform.fp;
        sje.errsrc = new DefaultErrorSource(sje.getClass().getName(), null);
        ErrorSource.registerErrorSource(sje.errsrc);
        sje.kif.filename = test;
    }

    @After
    public void afterTest() {
        ErrorSource.unregisterErrorSource(sje.errsrc);
        sje = null;
    }

    @Test // Will exercise SigmaAntlr parser
    public void testCheckErrorsBody() {

        System.out.println("============= SUMOjEditTest.testCheckErrorsBody ==================");
        String contents = String.join("\n", FileUtil.readLines(test, false));
        sje.checkErrorsBody(contents);

        ErrorSource[] errsrcs = ErrorSource.getErrorSources();
        assertTrue(errsrcs.length > 0);

        String msg = errsrcs[0].getFileErrors(test)[2].getErrorMessage();
        assertTrue(msg.contains("mismatched input ')'"));
    }

} // end class file SUMOjEditTest.java