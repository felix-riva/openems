package io.openems.impl.persistence.influxdb;

import java.net.Inet4Address;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.BatchPoints;
import org.influxdb.dto.Point;
import org.influxdb.dto.Point.Builder;

import com.google.common.collect.HashMultimap;

import io.openems.api.channel.Channel;
import io.openems.api.channel.ChannelUpdateListener;
import io.openems.api.channel.ConfigChannel;
import io.openems.api.channel.ReadChannel;
import io.openems.api.persistence.Persistence;
import io.openems.core.Databus;

public class InfluxdbPersistence extends Persistence implements ChannelUpdateListener {

	private final String DB_NAME = "db";

	private Optional<InfluxDB> _influxdb = Optional.empty();

	/*
	 * Config
	 */
	public final ConfigChannel<Integer> fems = new ConfigChannel<Integer>("fems", this, Integer.class);

	public final ConfigChannel<Inet4Address> ip = new ConfigChannel<Inet4Address>("ip", this, Inet4Address.class);

	public final ConfigChannel<String> username = new ConfigChannel<String>("username", this, String.class)
			.defaultValue("root");

	public final ConfigChannel<String> password = new ConfigChannel<String>("password", this, String.class)
			.defaultValue("root");

	private ConfigChannel<Integer> cycleTime = new ConfigChannel<Integer>("cycleTime", this, Integer.class)
			.defaultValue(10000);

	@Override public ConfigChannel<Integer> cycleTime() {
		return cycleTime;
	}

	private HashMultimap<Long, FieldValue<?>> queue = HashMultimap.create();

	/**
	 * Receives events for all {@link ReadChannel}s, excluding {@link ConfigChannel}s via the {@link Databus}.
	 */
	@Override public void channelUpdated(Channel channel, Optional<?> newValue) {
		if (!(channel instanceof ReadChannel<?>)) {
			return;
		}
		ReadChannel<?> readChannel = (ReadChannel<?>) channel;
		if (!newValue.isPresent()) {
			return;
		}
		Object value = newValue.get();
		String field = readChannel.address();
		FieldValue<?> fieldValue;
		if (value instanceof Number) {
			fieldValue = new NumberFieldValue(field, (Number) value);
		} else if (value instanceof String) {
			fieldValue = new StringFieldValue(field, (String) value);
		} else {
			return;
		}
		// Round time to Cycle-Time
		int cycleTime = this.cycleTime().valueOptional().get();
		Long timestamp = System.currentTimeMillis() / cycleTime * cycleTime;
		synchronized (queue) {
			queue.put(timestamp, fieldValue);
		}
	}

	@Override protected void dispose() {

	}

	@Override protected void forever() {
		// Prepare DB connection
		Optional<InfluxDB> _influxdb = getInfluxDB();
		if (!_influxdb.isPresent()) {
			synchronized (queue) {
				// Clear queue if we don't have a valid influxdb connection. This is necessary to avoid filling the
				// memory in case of no available DB connection
				queue.clear();
			}
		}
		InfluxDB influxDB = _influxdb.get();
		/*
		 * Convert FieldVales in queue to Points
		 */
		BatchPoints batchPoints = BatchPoints.database(DB_NAME) //
				.tag("fems", String.valueOf(fems.valueOptional().get())) //
				/* .retentionPolicy("autogen") */.build();
		synchronized (queue) {
			queue.asMap().forEach((timestamp, fieldValues) -> {
				Builder builder = Point.measurement("data") //
						.time(timestamp, TimeUnit.MILLISECONDS);
				fieldValues.forEach(fieldValue -> {
					if (fieldValue instanceof NumberFieldValue) {
						builder.addField(fieldValue.field, ((NumberFieldValue) fieldValue).value);
					} else if (fieldValue instanceof StringFieldValue) {
						builder.addField(fieldValue.field, ((StringFieldValue) fieldValue).value);
					}
				});
				batchPoints.point(builder.build());
			});
			queue.clear();
		}
		// write to DB
		influxDB.write(batchPoints);
		log.debug("Wrote [" + batchPoints.getPoints().size() + "] points to InfluxDB");
	}

	@Override protected boolean initialize() {
		if (getInfluxDB().isPresent()) {
			return true;
		} else {
			return false;
		}
	}

	private Optional<InfluxDB> getInfluxDB() {
		if (!this.ip.valueOptional().isPresent() || !this.fems.valueOptional().isPresent()
				|| !this.username.valueOptional().isPresent() || !this.password.valueOptional().isPresent()) {
			return Optional.empty();
		}

		if (_influxdb.isPresent()) {
			return this._influxdb;
		}

		String ip = this.ip.valueOptional().get().getHostAddress();
		String username = this.username.valueOptional().get();
		String password = this.password.valueOptional().get();

		InfluxDB influxdb = InfluxDBFactory.connect("http://" + ip + ":8086", username, password);
		try {
			influxdb.createDatabase(DB_NAME);
		} catch (RuntimeException e) {
			log.error("Unable to connect to InfluxDB: ", e);
			return Optional.empty();
		}

		this._influxdb = Optional.of(influxdb);
		return this._influxdb;
	}
}
