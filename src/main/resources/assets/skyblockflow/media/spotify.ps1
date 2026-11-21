$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
Add-Type -AssemblyName System.Runtime.WindowsRuntime
Add-Type -AssemblyName System.Drawing
$managerType=[Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]
$propsType=[Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties,Windows.Media.Control,ContentType=WindowsRuntime]
$streamType=[Windows.Storage.Streams.IRandomAccessStreamWithContentType,Windows.Storage.Streams,ContentType=WindowsRuntime]
$asTask=[System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {$_.Name -eq 'AsTask' -and $_.IsGenericMethod -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation' + [char]96 + '1'} | Select-Object -First 1
function Await($op,$type){
  $task=$asTask.MakeGenericMethod($type).Invoke($null,@($op))
  if(!$task.Wait(4000)){throw 'Media session timeout'}
  $task.Result
}
function Cover($ref){
  if(!$ref){return ''}
  $random=Await ($ref.OpenReadAsync()) $streamType
  $method=[System.IO.WindowsRuntimeStreamExtensions].GetMethods() | Where-Object {$_.Name -eq 'AsStream' -and $_.GetParameters().Length -eq 1} | Select-Object -First 1
  $stream=$method.Invoke($null,@($random))
  try {
      if($stream.Length -gt 8000000){return ''}
      $img=[Drawing.Image]::FromStream($stream)
      try {
        $thumb=[Drawing.Bitmap]::new($img,64,64)
        try {
          $mem=[IO.MemoryStream]::new()
          try {$thumb.Save($mem,[Drawing.Imaging.ImageFormat]::Png);return [Convert]::ToBase64String($mem.ToArray())} finally {$mem.Dispose()}
        } finally {$thumb.Dispose()}
      } finally {$img.Dispose()}
  } finally {$stream.Dispose()}
}
$spotify=$env:SKYBLOCKFLOW_SPOTIFY -ne '0'
$manager=if($spotify){Await ($managerType::RequestAsync()) $managerType}
$key='';$cover=''
do {
  $result=@{}
  try {
    if($spotify){
    $s=$manager.GetSessions() | Where-Object {$_.SourceAppUserModelId -match '(?i)spotify'} | Sort-Object {if($_.GetPlaybackInfo().PlaybackStatus.ToString() -eq 'Playing'){0}else{1}} | Select-Object -First 1
    if($s){
      $p=Await ($s.TryGetMediaPropertiesAsync()) $propsType
      $t=$s.GetTimelineProperties()
      $playing=$s.GetPlaybackInfo().PlaybackStatus.ToString() -eq 'Playing'
      $id=$p.Artist+'|'+$p.AlbumTitle+'|'+$p.Title
      if($id -ne $key){$cover='';try{$cover=Cover $p.Thumbnail}catch{};$key=$id}
      $pos=($t.Position-$t.StartTime).TotalMilliseconds
      if($playing){$pos += [Math]::Max(0,([DateTimeOffset]::UtcNow-$t.LastUpdatedTime).TotalMilliseconds)}
      $result=@{title=$p.Title;artist=$p.Artist;album=$p.AlbumTitle;playing=$playing;position=[long]$pos;duration=[long]($t.EndTime-$t.StartTime).TotalMilliseconds;art=$cover}
    } else {$key='';$cover=''}
    }
  } catch {}
  if($env:SKYBLOCKFLOW_DISCORD -eq '1'){
    foreach($proc in [Diagnostics.Process]::GetProcessesByName('Discord')){
      try {if($proc.MainWindowTitle -match '^\s*\((\d+)\+?\)'){$result.discord=[int]$Matches[1];break}}finally{$proc.Dispose()}
    }
  }
  [Console]::WriteLine(($result | ConvertTo-Json -Compress));[Console]::Out.Flush()
  if($env:SKYBLOCKFLOW_MEDIA_ONCE){break}
  Start-Sleep -Seconds 5
} while($true)
