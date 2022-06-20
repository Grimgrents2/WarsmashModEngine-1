package com.etheller.warsmash.networking.uberserver;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.etheller.warsmash.networking.uberserver.users.PasswordAuthentication;
import com.etheller.warsmash.networking.uberserver.users.User;
import com.etheller.warsmash.networking.uberserver.users.UserManager;

import net.warsmash.map.NetMapDownloader;
import net.warsmash.uberserver.AccountCreationFailureReason;
import net.warsmash.uberserver.ChannelServerMessageType;
import net.warsmash.uberserver.GameCreationFailureReason;
import net.warsmash.uberserver.GamingNetworkServerToClientListener;
import net.warsmash.uberserver.HandshakeDeniedReason;
import net.warsmash.uberserver.HostedGameVisibility;
import net.warsmash.uberserver.JoinGameFailureReason;
import net.warsmash.uberserver.LobbyGameSpeed;
import net.warsmash.uberserver.LoginFailureReason;
import net.warsmash.uberserver.ServerErrorMessageType;

public class GamingNetworkServerBusinessLogicImpl {
	private final Set<AcceptedGameListKey> acceptedGames;
	private final UserManager userManager;
	private final String welcomeMessage;
	private final Map<Integer, SessionImpl> userIdToCurrentSession = new HashMap<>();
	private final Map<Long, SessionImpl> tokenToSession;
	private final Map<String, ChatChannel> nameLowerCaseToChannel = new HashMap<>();
	private final Map<String, HostedGame> nameLowerCaseToGame = new HashMap<>();
	private final Random random;

	public GamingNetworkServerBusinessLogicImpl(final Set<AcceptedGameListKey> acceptedGames,
			final UserManager userManager, final String welcomeMessage) {
		this.acceptedGames = acceptedGames;
		this.userManager = userManager;
		this.welcomeMessage = welcomeMessage;
		this.tokenToSession = new HashMap<>();
		this.random = new Random();
	}

	public void disconnected(GamingNetworkServerToClientWriter writer) {
		// TODO this is not efficient, and may make DDOS hit us badly
		SessionImpl sessionToKill = null;
		for (final SessionImpl session : this.tokenToSession.values()) {
			if (session.mostRecentConnectionContext == writer) {
				sessionToKill = session;
			}
		}
		if (sessionToKill != null) {
			killSession(sessionToKill);
		}
	}

	public void handshake(final String gameId, final int version,
			final GamingNetworkServerToClientListener connectionContext) {
		if (this.acceptedGames.contains(new AcceptedGameListKey(gameId, version))) {
			connectionContext.handshakeAccepted();
		}
		else {
			connectionContext.handshakeDenied(HandshakeDeniedReason.BAD_GAME_VERSION);
		}
	}

	public void createAccount(final String username, final char[] passwordHash,
			final GamingNetworkServerToClientListener connectionContext) {
		final User user = this.userManager.createUser(username, passwordHash);
		if (user == null) {
			connectionContext.accountCreationFailed(AccountCreationFailureReason.USERNAME_ALREADY_EXISTS);
		}
		else {
			connectionContext.accountCreationOk();
		}
	}

	public void login(final String username, final char[] passwordHash,
			final GamingNetworkServerToClientListener connectionContext) {
		final User user = this.userManager.getUserByName(username);
		if (user != null) {
			if (PasswordAuthentication.authenticate(passwordHash, user.getPasswordHash())) {
				final SessionImpl currentSession = this.userIdToCurrentSession.get(user.getId());
				if (currentSession != null) {
					killSession(currentSession);
				}
				final long timestamp = System.currentTimeMillis();
				final SessionImpl session = new SessionImpl(user, timestamp, this.random.nextLong(), connectionContext);
				this.tokenToSession.put(session.getToken(), session);
				this.userIdToCurrentSession.put(user.getId(), session);
				connectionContext.loginOk(session.getToken(), GamingNetworkServerBusinessLogicImpl.this.welcomeMessage);
			}
			else {
				connectionContext.loginFailed(LoginFailureReason.INVALID_CREDENTIALS);
			}
		}
		else {
			connectionContext.loginFailed(LoginFailureReason.UNKNOWN_USER);
		}
	}

	private void killSession(final SessionImpl currentSession) {
		removeSessionFromCurrentChannel(currentSession);
		removeSessionFromCurrentGame(currentSession);
		this.tokenToSession.remove(currentSession.getToken());
		this.userIdToCurrentSession.remove(currentSession.getUser().getId());
		try {
			currentSession.mostRecentConnectionContext.disconnected();
		}
		catch (final Exception exc) {
			System.err.println("Exception while killing session for user: " + exc);
			exc.printStackTrace();
		}
	}

	public void joinChannel(final long sessionToken, final String channelName,
			final GamingNetworkServerToClientListener connectionContext) {
		final SessionImpl session = getSession(sessionToken, connectionContext);
		if (session != null) {
			removeSessionFromCurrentChannel(session);

			final String channelKey = channelName.toLowerCase(Locale.US);
			ChatChannel chatChannel = this.nameLowerCaseToChannel.get(channelKey);
			if (chatChannel == null) {
				chatChannel = new ChatChannel(channelName);
				this.nameLowerCaseToChannel.put(channelKey, chatChannel);
			}
			chatChannel.addUser(session);
			session.currentChatChannel = channelKey;
			session.lastActiveChatChannel = chatChannel.channelName;
			connectionContext.joinedChannel(channelName);
		}
		else {
			connectionContext.badSession();
		}
	}

	public void joinGame(final long sessionToken, final String gameName,
			final GamingNetworkServerToClientListener connectionContext) {
		final SessionImpl session = getSession(sessionToken, connectionContext);
		if (session != null) {
			if (session.currentGameName != null) {
				connectionContext.joinGameFailed(JoinGameFailureReason.SESSION_ERROR);
			}
			else {
				removeSessionFromCurrentChannel(session);

				final String gameKey = gameName.toLowerCase(Locale.US);
				final HostedGame game = this.nameLowerCaseToGame.get(gameKey);
				if (game == null) {
					connectionContext.joinGameFailed(JoinGameFailureReason.NO_SUCH_GAME);
				}
				else {
					if (game.userSessions.size() >= game.totalSlots) {
						connectionContext.joinGameFailed(JoinGameFailureReason.GAME_FULL);
					}
					else {
						game.addUser(session);
						session.currentGameName = gameKey;
						connectionContext.joinedGame(gameName, game.mapName, game.mapChecksum);
						game.sendServerMessage(session.getUser().getUsername(), ChannelServerMessageType.JOIN_GAME);
					}
				}
			}
		}
		else {
			connectionContext.badSession();
		}
	}

	public void leaveGame(long sessionToken, GamingNetworkServerToClientWriter connectionContext) {
		final SessionImpl session = getSession(sessionToken, connectionContext);
		if (session != null) {
			removeSessionFromCurrentGame(session);
			sendSessionToDefaultChannel(sessionToken, connectionContext, session);
		}
		else {
			connectionContext.badSession();
		}
	}

	public void uploadMapData(long sessionToken, int sequenceNumber, ByteBuffer data,
			GamingNetworkServerToClientWriter connectionContext) {
		final SessionImpl session = getSession(sessionToken, connectionContext);
		if (session != null) {
			if (session.currentGameName == null) {
				connectionContext.serverErrorMessage(ServerErrorMessageType.ERROR_HANDLING_REQUEST);
			}
			else {
				final HostedGame hostedGame = this.nameLowerCaseToGame.get(session.currentGameName);
				hostedGame.writeMap(sessionToken, sequenceNumber, data);
			}
		}
		else {
			connectionContext.badSession();
		}
	}

	public void mapDone(long sessionToken, int sequenceNumber, GamingNetworkServerToClientWriter connectionContext) {
		final SessionImpl session = getSession(sessionToken, connectionContext);
		if (session != null) {
			if (session.currentGameName == null) {
				connectionContext.serverErrorMessage(ServerErrorMessageType.ERROR_HANDLING_REQUEST);
			}
			else {
				final HostedGame hostedGame = this.nameLowerCaseToGame.get(session.currentGameName);
				if (!hostedGame.mapDone(sessionToken, sequenceNumber)) {
					closeGame(session.currentGameName, hostedGame);
					connectionContext.serverErrorMessage(ServerErrorMessageType.UPLOAD_MAP_FAILED);
				}
				else {
					hostedGame.sendMapToAwaitingUsers();
				}
			}
		}
		else {
			connectionContext.badSession();
		}
	}

	public void requestMap(long sessionToken, GamingNetworkServerToClientWriter connectionContext) {
		final SessionImpl session = getSession(sessionToken, connectionContext);
		if (session != null) {
			if (session.currentGameName == null) {
				connectionContext.serverErrorMessage(ServerErrorMessageType.ERROR_HANDLING_REQUEST);
			}
			else {
				final HostedGame hostedGame = this.nameLowerCaseToGame.get(session.currentGameName);
				if (hostedGame.isMapFullyLoaded()) {
					// easy case: send map
					System.err.println(session.getUser().getUsername() + " - sending map");
					hostedGame.sendMap(connectionContext);
				}
				else {
					System.err.println(
							session.getUser().getUsername() + " - waiting for map because server wasn't ready");
					hostedGame.addPlayerAwaitingMap(session);
				}
			}
		}
		else {
			connectionContext.badSession();
		}
	}

	private void sendSessionToDefaultChannel(SessionImpl session) {
		sendSessionToDefaultChannel(session.getToken(), session.mostRecentConnectionContext, session);
	}

	private void sendSessionToDefaultChannel(long sessionToken, GamingNetworkServerToClientListener connectionContext,
			final SessionImpl session) {
		if (session.lastActiveChatChannel != null) {
			joinChannel(sessionToken, session.lastActiveChatChannel, connectionContext);
		}
		else {
			// TODO maybe some other message here to let client know to return to
			// the welcome screen?? Should we just let them handle both of these cases?
			joinChannel(sessionToken, "Default Channel", connectionContext);
		}
	}

	public void createGame(long sessionToken, String gameName, String mapName, int totalSlots, LobbyGameSpeed gameSpeed,
			HostedGameVisibility visibility, long mapChecksum, GamingNetworkServerToClientWriter connectionContext) {
		final SessionImpl session = getSession(sessionToken, connectionContext);
		if (session != null) {

			final String gameKey = gameName.toLowerCase(Locale.US);
			HostedGame game = this.nameLowerCaseToGame.get(gameKey);
			if (game != null) {
				connectionContext.gameCreationFailed(GameCreationFailureReason.GAME_NAME_ALREADY_USED);
			}
			else {
				removeSessionFromCurrentChannel(session);
				game = new HostedGame(session.getUser(), gameName, mapName, totalSlots, gameSpeed, visibility,
						mapChecksum);
				this.nameLowerCaseToGame.put(gameKey, game);
				connectionContext.gameCreationOk();
				game.addUser(session);
				session.currentGameName = gameKey;
				connectionContext.joinedGame(gameName, game.mapName, game.mapChecksum);
				game.sendServerMessage(session.getUser().getUsername(), ChannelServerMessageType.JOIN_GAME);
			}
		}
		else {
			connectionContext.badSession();
		}
	}

	private void removeSessionFromCurrentChannel(final SessionImpl session) {
		final String previousChatChannel = session.currentChatChannel;
		if (previousChatChannel != null) {
			final String previousChannelKey = previousChatChannel.toLowerCase(Locale.US);
			final ChatChannel previousChannel = this.nameLowerCaseToChannel.get(previousChannelKey);
			previousChannel.removeUser(session);
			if (previousChannel.isEmpty()) {
				this.nameLowerCaseToChannel.remove(previousChannelKey);
			}
			session.currentChatChannel = null;
		}
	}

	private void removeSessionFromCurrentGame(final SessionImpl session) {
		final String previousGameName = session.currentGameName;
		if (previousGameName != null) {
			final String previousGameKey = previousGameName.toLowerCase(Locale.US);
			final HostedGame previousGame = this.nameLowerCaseToGame.get(previousGameKey);
			previousGame.removeUser(session);
			session.currentGameName = null;
			if (previousGame.isEmpty()) {
				this.nameLowerCaseToGame.remove(previousGameKey);
			}
			else if (session.getUser() == previousGame.getHostUser()) {
				// host leaves the game, but it is not empty.
				closeGame(previousGameKey, previousGame);

			}
			else {
				previousGame.sendServerMessage(session.getUser().getUsername(), ChannelServerMessageType.LEAVE_GAME);
			}
		}
	}

	private void closeGame(final String previousGameKey, final HostedGame previousGame) {
		// 1.) notify clients that they were booted from game
		for (final SessionImpl nonHostUserSession : previousGame.userSessions) {
			nonHostUserSession.currentGameName = null;
			sendSessionToDefaultChannel(nonHostUserSession);
		}
		// 2.) close down the game
		this.nameLowerCaseToGame.remove(previousGameKey);
		previousGame.onCloseGame();
	}

	public void chatMessage(final long sessionToken, final String text,
			final GamingNetworkServerToClientListener connectionContext) {
		final SessionImpl session = getSession(sessionToken, connectionContext);
		if (session != null) {
			if (session.currentChatChannel != null) {
				final String channelKey = session.currentChatChannel.toLowerCase(Locale.US);
				final ChatChannel chatChannel = this.nameLowerCaseToChannel.get(channelKey);
				if (chatChannel != null) {
					chatChannel.sendMessage(session.getUser().getUsername(), text);
				}
			}
			else if (session.currentGameName != null) {
				final String channelKey = session.currentGameName.toLowerCase(Locale.US);
				final HostedGame chatChannel = this.nameLowerCaseToGame.get(channelKey);
				if (chatChannel != null) {
					chatChannel.sendMessage(session.getUser().getUsername(), text);
				}
			}
			else {
				connectionContext.serverErrorMessage(ServerErrorMessageType.ERROR_HANDLING_REQUEST);
			}
		}
		else {
			connectionContext.badSession();
		}
	}

	public void emoteMessage(final long sessionToken, final String text,
			final GamingNetworkServerToClientListener connectionContext) {
		final SessionImpl session = getSession(sessionToken, connectionContext);
		if (session != null) {
			if (session.currentChatChannel != null) {
				final String channelKey = session.currentChatChannel.toLowerCase(Locale.US);
				final ChatChannel chatChannel = this.nameLowerCaseToChannel.get(channelKey);
				if (chatChannel != null) {
					chatChannel.sendEmote(session.getUser().getUsername(), text);
				}
			}
			else if (session.currentGameName != null) {
				final String channelKey = session.currentGameName.toLowerCase(Locale.US);
				final HostedGame chatChannel = this.nameLowerCaseToGame.get(channelKey);
				if (chatChannel != null) {
					chatChannel.sendEmote(session.getUser().getUsername(), text);
				}
			}
			else {
				connectionContext.serverErrorMessage(ServerErrorMessageType.ERROR_HANDLING_REQUEST);
			}
		}
		else {
			connectionContext.badSession();
		}
	}

	public void queryGamesList(final long sessionToken, final GamingNetworkServerToClientListener connectionContext) {
		final SessionImpl session = getSession(sessionToken, connectionContext);
		if (session != null) {
			// TODO this code paradigm is stupid and gives the client an incentive to modify
			// their client to request this as frequently as possible. That is stupid, and
			// the client should instead send us some form of subscribe message after which
			// we would send them a throttled feed. However, the setup for the throttled
			// feed felt like it would be a waste of time so in the interest of development
			// time, for now I am doing a basic request/reply that allows the client to ask
			// us for the games list whenever they want.
			connectionContext.beginGamesList();
			for (final Map.Entry<String, HostedGame> nameAndGame : this.nameLowerCaseToGame.entrySet()) {
				final HostedGame game = nameAndGame.getValue();
				if (game.visibility == HostedGameVisibility.PUBLIC) {
					connectionContext.gamesListItem(game.getGameName(), game.getUsedSlots(), game.getTotalSlots());
				}
			}
			connectionContext.endGamesList();
		}
		else {
			connectionContext.badSession();
		}
	}

	public void queryGameInfo(final long sessionToken, final GamingNetworkServerToClientWriter connectionContext) {
		final SessionImpl session = getSession(sessionToken, connectionContext);
		if (session != null) {
			// TODO
		}
		else {
			connectionContext.badSession();
		}
	}

	private SessionImpl getSession(final long token,
			final GamingNetworkServerToClientListener mostRecentConnectionContext) {
		final SessionImpl session = this.tokenToSession.get(token);
		if (session != null) {
			if (session.getLastActiveTime() < (System.currentTimeMillis() - (60 * 60 * 1000))) {
				killSession(session);
				return null;
			}
			else {
				session.notifyUsed(mostRecentConnectionContext);
				return session;
			}
		}
		return null;
	}

	private static final class SessionImpl {
		private final User user;
		private final long timestamp;
		private final long secretKey;
		private long lastActiveTime;
		private String lastActiveChatChannel;
		private String currentChatChannel;
		private String currentGameName;
		private GamingNetworkServerToClientListener mostRecentConnectionContext;

		public SessionImpl(final User user, final long timestamp, final long secretKey,
				final GamingNetworkServerToClientListener connectionContext) {
			this.user = user;
			this.timestamp = timestamp;
			this.secretKey = secretKey;
			this.lastActiveTime = timestamp;
			this.mostRecentConnectionContext = connectionContext;
		}

		public void notifyUsed(final GamingNetworkServerToClientListener mostRecentConnectionContext) {
			this.lastActiveTime = System.currentTimeMillis();
			this.mostRecentConnectionContext = mostRecentConnectionContext;
		}

		public long getLastActiveTime() {
			return this.lastActiveTime;
		}

		public User getUser() {
			return this.user;
		}

		public long getTimestamp() {
			return this.timestamp;
		}

		public long getToken() {
			final int nameCode = this.user.getUsername().hashCode();
			return (this.timestamp & 0xFFFFFFFF)
					| (((this.secretKey << 32) + ((long) nameCode << 32)) & 0xFFFFFFFF00000000L);
		}
	}

	private static final class ChatChannel {
		private final String channelName;
		private final List<SessionImpl> userSessions = new ArrayList<>();

		public ChatChannel(final String channelName) {
			this.channelName = channelName;
		}

		public void removeUser(final SessionImpl session) {
			this.userSessions.remove(session);
		}

		public void addUser(final SessionImpl session) {
			this.userSessions.add(session);
		}

		public boolean isEmpty() {
			return this.userSessions.isEmpty();
		}

		public void sendMessage(final String sourceUserName, final String message) {
			for (final SessionImpl session : this.userSessions) {
				try {
					session.mostRecentConnectionContext.channelMessage(sourceUserName, message);
				}
				catch (final Exception exc) {
					exc.printStackTrace();
				}
			}
		}

		public void sendEmote(final String sourceUserName, final String message) {
			for (final SessionImpl session : this.userSessions) {
				try {
					session.mostRecentConnectionContext.channelEmote(sourceUserName, message);
				}
				catch (final Exception exc) {
					exc.printStackTrace();
				}
			}
		}
	}

	private static final class HostedGame {
		private final User hostUser;
		private final String gameName;
		private final String mapName;
		private final int totalSlots;
		private final LobbyGameSpeed gameSpeed;
		private final HostedGameVisibility visibility;
		private final long mapChecksum;
		private final List<SessionImpl> userSessions = new ArrayList<>();
		private final List<SessionImpl> userSessionsAwaitingMap = new ArrayList<>();
		private NetMapDownloader mapDownloader;
		private File mapFile;
		private boolean mapFullyLoaded = false;

		public HostedGame(User hostUser, final String gameName, String mapName, final int totalSlots,
				LobbyGameSpeed gameSpeed, HostedGameVisibility visibility, long mapChecksum) {
			this.hostUser = hostUser;
			this.gameName = gameName;
			this.mapName = mapName;
			this.totalSlots = totalSlots;
			this.gameSpeed = gameSpeed;
			this.visibility = visibility;
			this.mapChecksum = mapChecksum;
		}

		public boolean mapDone(long sessionToken, int sequenceNumber) {
			final long finishedChecksum = this.mapDownloader.finish(sequenceNumber);
			this.mapFullyLoaded = (finishedChecksum == this.mapChecksum) && this.mapDownloader.isSequenceNumberingOK();
			return this.mapFullyLoaded;
		}

		public void sendMap(GamingNetworkServerToClientListener connectionContext) {
			int sequenceNumber = 0;
			connectionContext.beginSendMap();
			try (FileChannel readerChannel = FileChannel.open(this.mapFile.toPath(), StandardOpenOption.READ)) {
				final ByteBuffer readBuffer = ByteBuffer.allocate(1300).clear();
				while ((readerChannel.read(readBuffer)) != -1) {
					readBuffer.flip();
					connectionContext.sendMapData(sequenceNumber++, readBuffer);
					readBuffer.clear();
				}
			}
			catch (final IOException e) {
				throw new IllegalStateException(e);
			}
			connectionContext.endSendMap(sequenceNumber);
		}

		public boolean isMapFullyLoaded() {
			return this.mapFullyLoaded;
		}

		public void writeMap(long sessionToken, int sequenceNumber, ByteBuffer data) {
			if (this.mapDownloader == null) {
				final int id = this.hostUser.getId();
				final String nameToStoreMap = id + "_" + sessionToken + "_";
				try {
					this.mapFile = File.createTempFile(nameToStoreMap, ".dat");
				}
				catch (final IOException e) {
					throw new IllegalStateException(e);
				}
				this.mapDownloader = new NetMapDownloader(this.mapFile);
			}
			this.mapDownloader.receive(sequenceNumber, data);
		}

		public User getHostUser() {
			return this.hostUser;
		}

		public void removeUser(final SessionImpl session) {
			this.userSessions.remove(session);
		}

		public void addUser(final SessionImpl session) {
			this.userSessions.add(session);
		}

		public void addPlayerAwaitingMap(SessionImpl session) {
			this.userSessionsAwaitingMap.add(session);
		}

		public boolean isEmpty() {
			return this.userSessions.isEmpty();
		}

		public String getGameName() {
			return this.gameName;
		}

		public int getUsedSlots() {
			return this.userSessions.size();
		}

		public int getTotalSlots() {
			return this.totalSlots;
		}

		public void sendMessage(final String sourceUserName, final String message) {
			for (final SessionImpl session : this.userSessions) {
				try {
					session.mostRecentConnectionContext.channelMessage(sourceUserName, message);
				}
				catch (final Exception exc) {
					exc.printStackTrace();
				}
			}
		}

		public void sendEmote(final String sourceUserName, final String message) {
			for (final SessionImpl session : this.userSessions) {
				try {
					session.mostRecentConnectionContext.channelEmote(sourceUserName, message);
				}
				catch (final Exception exc) {
					exc.printStackTrace();
				}
			}
		}

		public void sendServerMessage(final String sourceUserName, final ChannelServerMessageType message) {
			for (final SessionImpl session : this.userSessions) {
				try {
					session.mostRecentConnectionContext.channelServerMessage(sourceUserName, message);
				}
				catch (final Exception exc) {
					exc.printStackTrace();
				}
			}
		}

		public void sendMapToAwaitingUsers() {
			for (final SessionImpl session : this.userSessionsAwaitingMap) {
				sendMap(session.mostRecentConnectionContext);
			}
			this.userSessionsAwaitingMap.clear();
		}

		public void onCloseGame() {
			if (this.mapFile != null) {
				this.mapFile.delete();
			}
		}
	}

}
