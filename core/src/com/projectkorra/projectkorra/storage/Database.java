package com.projectkorra.projectkorra.storage;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;

public abstract class Database {

	protected final Logger log;
	protected final String dbprefix;
	protected Connection connection = null;
	/**
	 * All asynchronous database work runs on this single thread, so it runs in the order it was submitted
	 */
	private ExecutorService executor;
	private volatile Thread executorThread;

	public Database(final Logger log, final String dbprefix) {
		this.log = log;
		this.dbprefix = dbprefix;
	}

	/**
	 * Print information to console.
	 *
	 * @param message The string to print to console
	 */
	protected void printInfo(final String message) {
		this.log.info(this.dbprefix + message);
	}

	/**
	 * Print error to console.
	 *
	 * @param message The string to print to console
	 * @param severe If {@param severe} is true print an error, else print a
	 *            warning
	 */
	protected void printErr(final String message, final boolean severe) {
		if (severe) {
			this.log.severe(this.dbprefix + message);
		} else {
			this.log.warning(this.dbprefix + message);
		}
	}

	/**
	 * Returns the current Connection.
	 *
	 * @return Connection if exists, else null
	 */
	public Connection getConnection() {
		return this.connection;
	}

	/**
	 * Opens connection to Database.
	 *
	 * @return Connection if successful
	 */
	abstract Connection open();

	private synchronized ExecutorService getExecutor() {
		if (this.executor == null || this.executor.isShutdown()) {
			this.executor = Executors.newSingleThreadExecutor(runnable -> {
				final Thread thread = new Thread(runnable, "ProjectKorra Database");
				thread.setDaemon(true);
				this.executorThread = thread;
				return thread;
			});
		}
		return this.executor;
	}

	/**
	 * Runs a task on the database thread. Tasks run one at a time, in the order they were submitted, so a read
	 * submitted after a write will see that write.
	 *
	 * @param task The task to run
	 * @return A future that completes, off the main thread, when the task has run
	 */
	public CompletableFuture<Void> runAsync(final Runnable task) {
		return CompletableFuture.runAsync(task, this.getExecutor()).whenComplete((v, e) -> {
			if (e != null) {
				e.printStackTrace();
			}
		});
	}

	/**
	 * Runs a task that returns a result on the database thread. See {@link #runAsync(Runnable)}.
	 *
	 * @param task The task to run
	 * @return A future that completes, off the main thread, with the result
	 */
	public <T> CompletableFuture<T> supplyAsync(final Supplier<T> task) {
		return CompletableFuture.supplyAsync(task, this.getExecutor()).whenComplete((v, e) -> {
			if (e != null) {
				e.printStackTrace();
			}
		});
	}

	/**
	 * Waits for all queued asynchronous database work to finish
	 */
	private void flush() {
		final ExecutorService executor;
		synchronized (this) {
			executor = this.executor;
		}
		if (executor == null || Thread.currentThread() == this.executorThread) {
			return;
		}
		executor.shutdown();
		try {
			if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
				this.printErr("Timed out waiting for queued database queries to finish.", true);
			}
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Close connection to Database. Queued asynchronous queries are run first.
	 */
	public void close() {
		this.flush();
		if (this.connection != null) {
			try {
				this.connection.setAutoCommit(false);
				this.connection.commit(); //Force all uncommitted changes to be written before closing
				this.connection.close();
			} catch (final SQLException e) {
				e.printStackTrace();
			}
		} else {
			this.printErr("There was no SQL connection open.", false);
		}
	}

	/**
	 * Queries the Database, for queries which modify data. Run async by
	 * default.
	 *
	 * @param query Query to run
	 */
	public void modifyQuery(final String query, final Object... args) {
		this.modifyQueryAsync(query, args);
	}

	/**
	 * Queries the Databases, for queries which modify data. Run asynchronously on the database thread, in the
	 * order they were submitted.
	 *
	 * @param query Query to run
	 */
	public void modifyQueryAsync(final String query, final Object... args) {
		this.runAsync(() -> this.doQuery(query, args));
	}

	/**
	 * Queries the Databases, for queries which modify data. Run synchronously.
	 *
	 * @param query Query to run
	 */
	public void modifyQuerySync(final String query, final Object... args) {
		this.doQuery(query, args);
	}

	/**
	 * Queries the Database, for queries which return results.
	 *
	 * @param query Query to run
	 * @return Result set of ran query
	 */
	public ResultSet readQuery(final String query, final Object... args) {
		try {
			if (this.connection == null || this.connection.isClosed()) {
				this.open();
			}
			final PreparedStatement stmt = this.connection.prepareStatement(query);
			if (args != null && args.length > 0) {
				for (int i = 0; i < args.length; i++) {
					if (args[i] != null) {
						switch (args[i]) {
							case String s -> stmt.setString(i + 1, s);
							case Integer integer -> stmt.setInt(i + 1, integer);
							case Long l -> stmt.setLong(i + 1, l);
							case Float v -> stmt.setFloat(i + 1, v);
							case Double v -> stmt.setDouble(i + 1, v);
							case Boolean b -> stmt.setBoolean(i + 1, b);
							case null, default -> stmt.setObject(i + 1, args[i]);
						}
					} else {
						stmt.setNull(i + 1, java.sql.Types.NULL);
					}
				}
			}
			// Close the statement together with the ResultSet, so callers closing the ResultSet don't leak it
			stmt.closeOnCompletion();
			final ResultSet rs = stmt.executeQuery();

			return rs;
		} catch (final SQLException e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * Check database to see if a table exists.
	 *
	 * @param table Table name to check
	 * @return true if table exists, else false
	 */
	public boolean tableExists(final String table) {
		try {
			if (this.connection == null || this.connection.isClosed()) {
				this.open();
			}
			final DatabaseMetaData dmd = this.connection.getMetaData();
			try (ResultSet rs = dmd.getTables(null, null, table, null)) {
				return rs.next();
			}
		} catch (final Exception e) {
			e.printStackTrace();
			return false;
		}
	}

	/**
	 * Check database to see if column exists within table.
	 *
	 * @param table Table name to check
	 * @param column Column name to check
	 * @return true if column exists within table, else false
	 */
	public boolean columnExists(final String table, final String column) {
		try {
			if (this.connection == null || this.connection.isClosed()) {
				this.open();
			}
			final DatabaseMetaData dmd = this.connection.getMetaData();
			try (ResultSet rs = dmd.getColumns(null, null, table, column)) {
				return rs.next();
			}
		} catch (final Exception e) {
			e.printStackTrace();
			return false;
		}
	}

	private synchronized void doQuery(final String query, final Object... args) {
		try {
			if (this.connection == null || this.connection.isClosed()) {
				this.open();
			}
			final PreparedStatement stmt = this.connection.prepareStatement(query);
			if (args != null && args.length > 0) {
				for (int i = 0; i < args.length; i++) {
					if (args[i] != null) {
                        switch (args[i]) {
                            case String s -> stmt.setString(i + 1, s);
                            case Integer integer -> stmt.setInt(i + 1, integer);
                            case Long l -> stmt.setLong(i + 1, l);
                            case Float v -> stmt.setFloat(i + 1, v);
                            case Double v -> stmt.setDouble(i + 1, v);
							case Boolean b -> stmt.setBoolean(i + 1, b);
                            case null, default -> stmt.setObject(i + 1, args[i]);
                        }
					} else {
						stmt.setNull(i + 1, java.sql.Types.NULL);
					}
				}
			}
			stmt.execute();
			stmt.close();
		} catch (final SQLException e) {
			e.printStackTrace();
		}
	}

}
