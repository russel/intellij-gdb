package uk.co.cwspencer.ideagdb.debug;

import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.ui.ExecutionConsole;
import com.intellij.execution.ui.RunnerLayoutUi;
import com.intellij.execution.ui.layout.PlaceInGrid;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.content.Content;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointHandler;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import org.jetbrains.annotations.NotNull;
import uk.co.cwspencer.gdb.Gdb;
import uk.co.cwspencer.gdb.GdbListener;
import uk.co.cwspencer.gdb.messages.GdbEvent;
import uk.co.cwspencer.gdb.messages.GdbRunningEvent;
import uk.co.cwspencer.gdb.messages.GdbStoppedEvent;
import uk.co.cwspencer.ideagdb.debug.breakpoints.GdbBreakpointHandler;
import uk.co.cwspencer.ideagdb.debug.breakpoints.GdbBreakpointProperties;
import uk.co.cwspencer.ideagdb.facet.GdbFacet;
import uk.co.cwspencer.gdb.gdbmi.GdbMiResultRecord;
import uk.co.cwspencer.gdb.gdbmi.GdbMiStreamRecord;
import uk.co.cwspencer.ideagdb.run.GdbExecutionResult;

import java.io.File;
import java.io.IOException;

public class GdbDebugProcess extends XDebugProcess implements GdbListener
{
	private static final Logger m_log =
		Logger.getInstance("#uk.co.cwspencer.ideagdb.debug.GdbDebugProcess");

	private GdbDebuggerEditorsProvider m_editorsProvider = new GdbDebuggerEditorsProvider();
	private ConsoleView m_console;

	// The GDB console
	private GdbConsoleView m_gdbConsole;

	// The GDB facet
	private GdbFacet m_facet;

	// The GDB instance
	private Gdb m_gdb;

	// The breakpoint handler
	private GdbBreakpointHandler m_breakpointHandler;

	/**
	 * Constructor; launches GDB.
	 */
	public GdbDebugProcess(XDebugSession session, GdbExecutionResult executionResult)
	{
		super(session);
		m_console = (ConsoleView) executionResult.getExecutionConsole();
		m_facet = executionResult.getFacet();

		// Get the working directory
		// TODO: Make this an option on the facet
		String workingDirectory = new File(m_facet.getConfiguration().APP_PATH).getParent();

		// Prepare GDB
		m_gdb = new Gdb(m_facet.getConfiguration().GDB_PATH, workingDirectory, this);

		// Create the GDB console
		m_gdbConsole = new GdbConsoleView(m_gdb, m_facet.getModule().getProject());

		// Create the breakpoint handler
		m_breakpointHandler = new GdbBreakpointHandler(m_gdb, this);

		// Launch the process
		m_gdb.start();
	}

	@Override
	public XBreakpointHandler<?>[] getBreakpointHandlers()
	{
		return new GdbBreakpointHandler[]{ m_breakpointHandler };
	}

	@NotNull
	@Override
	public XDebuggerEditorsProvider getEditorsProvider()
	{
		return m_editorsProvider;
	}

	/**
	 * Steps over the next line.
	 */
	@Override
	public void startStepOver()
	{
		try
		{
			m_gdb.sendCommand("-exec-next");
		}
		catch (IOException ex)
		{
			onGdbError(ex);
		}
	}

	@Override
	public void startPausing()
	{
		// TODO: GDB doesn't support handling commands when the target is running; we should use
		// asynchronous mode if the target supports it. I'm not really sure how to deal with this on
		// other targets (e.g., Windows)
		m_log.warn("startPausing: stub");
	}

	/**
	 * Steps into the next line.
	 */
	@Override
	public void startStepInto()
	{
		try
		{
			m_gdb.sendCommand("-exec-step");
		}
		catch (IOException ex)
		{
			onGdbError(ex);
		}
	}

	/**
	 * Steps out of the current function.
	 */
	@Override
	public void startStepOut()
	{
		try
		{
			m_gdb.sendCommand("-exec-finish");
		}
		catch (IOException ex)
		{
			onGdbError(ex);
		}
	}

	/**
	 * Stops program execution and exits GDB.
	 */
	@Override
	public void stop()
	{
		try
		{
			m_gdb.sendCommand("-gdb-exit");
		}
		catch (IOException ex)
		{
			onGdbError(ex);
		}
	}

	/**
	 * Resumes program execution.
	 */
	@Override
	public void resume()
	{
		try
		{
			m_gdb.sendCommand("-exec-continue --all");
		}
		catch (IOException ex)
		{
			onGdbError(ex);
		}
	}

	@Override
	public void runToPosition(@NotNull XSourcePosition position)
	{
		m_log.warn("runToPosition: stub");
	}

	@NotNull
	@Override
	public ExecutionConsole createConsole()
	{
		return m_console;
	}

	/**
	 * Called when the debugger UI is created so we can add our own content.
	 * @param ui The debugger UI.
	 */
	@Override
	public void registerAdditionalContent(@NotNull RunnerLayoutUi ui)
	{
		Content gdbConsoleContent = ui.createContent("GdbConsoleContent",
			m_gdbConsole.getComponent(), "GDB Console", AllIcons.Debugger.Console,
			m_gdbConsole.getPreferredFocusableComponent());
		gdbConsoleContent.setCloseable(false);

		// Create the actions
		final DefaultActionGroup consoleActions = new DefaultActionGroup();
		AnAction[] actions = m_gdbConsole.getConsole().createConsoleActions();
		for (AnAction action : actions)
		{
			consoleActions.add(action);
		}
		gdbConsoleContent.setActions(consoleActions, ActionPlaces.DEBUGGER_TOOLBAR,
			m_gdbConsole.getConsole().getPreferredFocusableComponent());

		ui.addContent(gdbConsoleContent, 2, PlaceInGrid.bottom, false);
	}

	/**
	 * Called when a GDB error occurs.
	 * @param ex The exception
	 */
	@Override
	public void onGdbError(final Throwable ex)
	{
		m_log.error("GDB error", ex);
	}

	/**
	 * Called when GDB has started.
	 */
	@Override
	public void onGdbStarted()
	{
		try
		{
			// Send startup commands
			String[] commandsArray = m_facet.getConfiguration().STARTUP_COMMANDS.split("\\r?\\n");
			for (String command : commandsArray)
			{
				command = command.trim();
				if (!command.isEmpty())
				{
					m_gdb.sendCommand(command);
				}
			}
		}
		catch (IOException ex)
		{
			onGdbError(ex);
		}
	}

	/**
	 * Called whenever a command is sent to GDB.
	 * @param command The command that was sent.
	 * @param token The token the command was sent with.
	 */
	@Override
	public void onGdbCommandSent(String command, long token)
	{
		m_gdbConsole.getConsole().print(token + "> " + command + "\n",
			ConsoleViewContentType.USER_INPUT);
	}

	/**
	 * Called when a GDB event is received.
	 * @param event The event.
	 */
	@Override
	public void onGdbEventReceived(GdbEvent event)
	{
		if (event instanceof GdbStoppedEvent)
		{
			// Target has stopped
			onGdbStoppedEvent((GdbStoppedEvent) event);
		}
		else if (event instanceof GdbRunningEvent)
		{
			// Target has started
			getSession().sessionResumed();
		}
	}

	/**
	 * Handles a 'target stopped' event from GDB.
 	 * @param event The event
	 */
	private void onGdbStoppedEvent(GdbStoppedEvent event)
	{
		GdbSuspendContext suspendContext = new GdbSuspendContext(m_gdb, event);

		// Find the breakpoint if necessary
		XBreakpoint<GdbBreakpointProperties> breakpoint = null;
		if (event.reason == GdbStoppedEvent.Reason.BreakpointHit && event.breakpointNumber != null)
		{
			breakpoint = m_breakpointHandler.findBreakpoint(event.breakpointNumber);
		}

		if (breakpoint != null)
		{
			// TODO: Support log expressions
			boolean suspendProcess = getSession().breakpointReached(breakpoint, null,
				suspendContext);
			if (!suspendProcess)
			{
				// Resume execution
				resume();
			}
		}
		else
		{
			getSession().positionReached(suspendContext);
		}
	}

	/**
	 * Called when a stream record is received.
	 * @param record The record.
	 */
	@Override
	public void onStreamRecordReceived(GdbMiStreamRecord record)
	{
		// Log the record
		switch (record.type)
		{
		case Console:
			StringBuilder sb = new StringBuilder();
			if (record.userToken != null)
			{
				sb.append("<");
				sb.append(record.userToken);
				sb.append(" ");
			}
			sb.append(record.message);
			m_gdbConsole.getConsole().print(sb.toString(), ConsoleViewContentType.NORMAL_OUTPUT);
			break;

		case Target:
			m_console.print(record.message, ConsoleViewContentType.NORMAL_OUTPUT);
			break;

		case Log:
			m_gdbConsole.getConsole().print(record.message, ConsoleViewContentType.SYSTEM_OUTPUT);
			break;
		}
	}

	/**
	 * Called when a result record is received.
	 * @param record The record.
	 */
	@Override
	public void onResultRecordReceived(GdbMiResultRecord record)
	{
		// Log the record
		StringBuilder sb = new StringBuilder();
		if (record.userToken != null)
		{
			sb.append("<");
			sb.append(record.userToken);
			sb.append(" ");
		}
		else
		{
			sb.append("< ");
		}

		switch (record.type)
		{
		case Immediate:
			sb.append("[immediate] ");
			break;

		case Exec:
			sb.append("[exec] ");
			break;

		case Notify:
			sb.append("[notify] ");
			break;

		case Status:
			sb.append("[status] ");
			break;
		}

		sb.append(record);
		sb.append("\n");
		m_gdbConsole.getConsole().print(sb.toString(), ConsoleViewContentType.SYSTEM_OUTPUT);
	}
}
