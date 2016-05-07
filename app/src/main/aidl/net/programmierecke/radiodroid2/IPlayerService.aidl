package net.programmierecke.radiodroid2;
interface IPlayerService
{
void Play(String theUrl,String theName,String theID);
void Stop();
void addTimer(int secondsAdd);
void clearTimer();
long getTimerSeconds();
String getCurrentStationID();
Map getMetadataLive();
String getMetadataStreamName();
String getMetadataServerName();
String getMetadataGenre();
String getMetadataHomepage();
int getMetadataBitrate();
int getMetadataSampleRate();
int getMetadataChannels();
}
