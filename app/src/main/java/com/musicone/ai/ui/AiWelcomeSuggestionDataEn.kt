package com.musicone

import com.musicone.AiWelcomeCategory.*
import com.musicone.AiWelcomeSeason.*
import com.musicone.AiWelcomeSlot.*

/** 英文版灵感句（备用，尚未接入界面）；结构与标签同中文版，艺人侧重国际与西语。 */
internal val aiWelcomeSuggestionPoolEn: List<AiWelcomeSuggestion> by lazy {
    timePool() + scenePool() + moodPool() + genrePool() + artistPool() + listeningPool() + playlistPool() + playbackPool() + knowledgePool()
}

/** 英文版大标题与副标题池。 */
internal val aiWelcomeHeadingsEn: List<AiWelcomeHeading> by lazy {
    listOf(
        AiWelcomeHeading("Hi, what are we listening to today?", "Tell me how you're doing, or let me pick a few songs."),
        AiWelcomeHeading("What's the mood today?", "Happy, spacing out, or need some quiet? I'll find something that fits."),
        AiWelcomeHeading("Something new, or an old favorite?", "Fresh sounds, or back to the melodies you know by heart?"),
        AiWelcomeHeading("Got your headphones on?", "Pick a cozy playlist, or let me put one together right now."),
        AiWelcomeHeading("No idea what to play? Leave it to me.", "Name an artist, a genre or a scene. I'll handle the rest."),
        AiWelcomeHeading("Good to see you. Music?", "Talk records with me anytime, or let's dig through your playlists."),
        AiWelcomeHeading("You set the pace.", "Energy, calm or zoning out? I've got all three."),
        AiWelcomeHeading("The music radar is on.", "Tell me which melody hooked you and I'll follow the thread."),
        AiWelcomeHeading("A good day to slow down and listen.", "Tell me what's on repeat, or let me tidy up your messy playlists."),
        AiWelcomeHeading("I'm patient about finding songs.", "Hidden gems or late-night company, just describe the feeling."),
        AiWelcomeHeading("Life needs a soundtrack.", "Working, walking, showering or daydreaming? Pick a song to keep you company."),
        AiWelcomeHeading("Chat freely, music anytime.", "Let me review your taste, or gather your favorites into a new playlist."),
        AiWelcomeHeading("Ears getting bored?", "Let me dig out a few gems you might love, and we'll listen together."),
        AiWelcomeHeading("Where do you want to go, musically?", "Vintage rock, gentle folk or city jazz? Let's head out."),
        AiWelcomeHeading("Been a while since you found new music?", "Tell me who you love and I'll find kindred spirits."),
        AiWelcomeHeading("Busy with something?", "Some background music that won't get in the way."),
        AiWelcomeHeading("How's the weather outside?", "Sunny, rainy or windy? Music always finds a way to match."),
        AiWelcomeHeading("Something healing, or something loud?", "Wrapped in softness, or rattled by some heavy bass?"),
        AiWelcomeHeading("Ears deserve to be picky too.", "Tired of overplayed hits? Let me find you something with taste."),
        AiWelcomeHeading("Which song have you moved into lately?", "Tell me what's on loop and I'll grow a whole forest from it."),
        AiWelcomeHeading("Music is the best shelter.", "Shut out the noise. These few minutes belong to you and your headphones."),
        AiWelcomeHeading("Which retro road today?", "Vaporwave, 80s City Pop, or old-school disco?"),
        AiWelcomeHeading("Feeling a little down?", "No lectures. Just a couple of slow songs that get it."),
        AiWelcomeHeading("Heading out for a walk?", "Bring the breeze and a beat. A few songs to stroll to."),
        AiWelcomeHeading("When did your playlists last get a cleanup?", "Too messy? I'll sort them out by style."),
        AiWelcomeHeading("A good night for a drink.", "Some grooving R&B and Soul to set the mood.", setOf(EVENING, LATE_NIGHT)),
        AiWelcomeHeading("How about a music mystery box?", "Three songs you've never heard that'll hook you in a second."),
        AiWelcomeHeading("Which intro got you hooked?", "Can't name it? Just describe the feeling."),
        AiWelcomeHeading("Sometimes you want to be a kid again.", "Some 2000s hits from an era full of legends."),
        AiWelcomeHeading("Need help focusing?", "Lyric-free ambience or Lo-Fi to get you in the zone."),
        AiWelcomeHeading("Who do you want to discover today?", "Indie songwriters and hidden-gem bands, I've got plenty."),
        AiWelcomeHeading("However busy life gets, breathe.", "Sink into the couch with a couple of songs light as a feather."),
        AiWelcomeHeading("Need a recharge?", "Punchy funk and fiery punk to power you through the rest of the day."),
        AiWelcomeHeading("Share a pair of earbuds?", "Let me dig up a few great records that are easy to miss."),
        AiWelcomeHeading("How are the old friends in your playlists?", "Revisit old favorites and see who got you through what."),
        AiWelcomeHeading("Feel like heading somewhere far away?", "Road-trip folk and desert psychedelia. Let your ears travel first."),
        AiWelcomeHeading("Another day of living well.", "Warm, strong melodies as a reward for working hard."),
        AiWelcomeHeading("Night owls' hour.", "In the small hours, only music and the ceiling understand.", setOf(LATE_NIGHT)),
        AiWelcomeHeading("Looking for a certain texture?", "Vinyl crackle, cassette hiss, or the creak of an acoustic guitar?"),
        AiWelcomeHeading("Some feelings are better said by lyrics.", "Want to open up but don't know how? Try these poetic songs."),
        AiWelcomeHeading("Let your mind go blank for a while.", "Think of nothing. Let a river of instrumentals carry you."),
        AiWelcomeHeading("No adulting today. Something light.", "Playful, cheerful, even slightly weird and cute tunes."),
        AiWelcomeHeading("That feeling when the credits roll.", "A few story-driven soundtracks to put a filter on your day."),
        AiWelcomeHeading("Something cool and refined?", "Icy Nordic electronica and British cold wave to cool your ears down."),
        AiWelcomeHeading("Anything goes, no right answers.", "There's no ranking in music. What you want right now is the best."),
        AiWelcomeHeading("Cooking or showering?", "The background music needs flavor. Sway along with the steam."),
        AiWelcomeHeading("A soundtrack for your memories.", "Which year, which city, which person? One melody brings it all back."),
        AiWelcomeHeading("Feeling emo? Don't force it.", "Music may not fix things, but it can sit with you for a while."),
        AiWelcomeHeading("Which far-off music scene today?", "Icelandic post-rock, French chanson, or fiery South American rhythms?"),
        AiWelcomeHeading("Music understands everything you leave unsaid.", "Pick a comfortable record and leave the rest to me.")
    )
}

/** 时段句。 */
private fun timePool() = aiWelcomePool(TIME) {
    during(DAWN) {
        +"Good morning! Some bright songs to wake up my ears"
        +"First coffee of the day, with some cozy acoustic guitar"
        +"Sunny melodies for breakfast"
        +"Stretching out, play some airy morning instrumentals"
        +"Light, steady music for a morning run or walk"
        +"Start the day with some upbeat indie pop"
        +"Soft folk for the first light of morning"
        +"Wake-up tunes with a beat, but nothing too loud"
        +"Just woke up and still groggy, some gentle songs to boot up"
        +"Something bright while I wash up, chase the sleepiness away"
        +"Commute songs that perk me up without being noisy"
        +"Packed morning train, something soothing in my ears"
        +"Early walk in the park, nature-inspired music with birdsong vibes"
        +"Light jazz for making breakfast"
        +"First song of the day, something hopeful"
        +"Daybreak, hazy ambient like morning fog"
        +"Ten minutes of morning stretching, some flowing instrumentals"
        +"Sunny Bossa Nova to go with breakfast"
        +"Out early, some brisk Britpop for the road"
        +"Quiet morning outside the window, a few soft piano pieces"
        +"Morning shower, some catchy oldies to hum along to"
        +"Cycling out in the morning, songs that feel like the wind"
        +"Woke up grumpy, some cute, healing feel-good songs"
        +"Set the tone for the day with some bright City Pop"
        +"What should I play at six in the morning in the city?"
        +"Steady-paced electronica for a morning run"
        +"Morning tea time, some delicate East Asian instrumentals"
        +"Morning playlist: gentle, bright, unhurried"
        +"First rays of light, with some fresh Japanese folk"
        +"Upbeat female vocals while I get ready in the morning"
        +"Morning jazz as warm as fresh coffee"
        +"Driving the kids to school, something light and cheerful"
        +"Start today with a big sing-along anthem"
        +"Don't feel like talking this morning, some quiet instrumentals"
        +"Sunbathing after getting up, some warm folk songs"
        +"City folk for the morning bus"
        +"Want to wake up slowly, some minimal piano"
        +"Up early to watch the sunrise, some epic score music"
        +"Something as fresh as the breeze on a morning run"
        +"Tidying up in the morning, some retro disco"
        onWeekend {
            +"Weekend morning, no alarm, some lazy songs to stay in bed to"
            +"Weekend morning at the market, something homey"
        }
    }
    during(MORNING) {
        +"Sunny late morning, some bright indie pop"
        +"Energizing songs to kick off the morning"
        +"A little sleepy at ten, some funk that wakes me up quietly"
        +"Second coffee of the morning, with some easy soul"
        +"Running errands this morning, some light pop for the way"
        +"Sitting in a café this morning, some café jazz"
        +"Crafting this morning, a few gentle folk songs"
        +"Sun on my desk, some Japanese ambient piano"
        +"Driving out this morning, some upbeat road songs"
        +"Bored in a queue, some breezy tunes"
        +"Upbeat songs for a late-morning stroll"
        +"Don't want to rush this morning, some soothing neo-classical"
        +"Upbeat songs for tidying the room"
        +"Keep the morning cheerful with some sunny oldies"
        +"Painting this morning, some inspiring ambient music"
        +"Reading for fun this morning, what Baroque music fits?"
        +"Been busy for a while, a few relaxing songs for a break"
        +"Some lovely piano solos before I practice"
        +"Strolling a flower market, something fresh"
        +"Airing out the bedding, some warm and sunny songs"
        +"Watering the plants, some pastoral music"
        +"Walking in the park, some light little tunes"
        +"Tea on the balcony, some delicate instrumentals"
        +"Cycling out, some crisp indie rock"
        +"Cooking myself something nice, with some light Bossa Nova"
        +"Leaving for a short trip, songs for the road"
        +"Blue skies this morning, some wide-open songs"
        +"Something easy that doesn't need any brainpower"
        +"Sorting the closet, some oldies to hum along to"
        +"Going for a hike, some wide-open songs"
        +"Out with family, songs everyone in the car will like"
        +"Daydreaming by the window, some quiet folk"
        +"Light rain this morning, some gentle piano"
        +"Change of mood, some breezy City Pop"
        +"Studied for a while, time for a break with some light rock"
        +"Meeting friends, something happy on the way"
        +"Washing the car, some upbeat rock"
        +"Something full of energy, Japanese pop style"
        +"Lovely breeze this morning, some fresh folk"
        +"Brewing a pot of tea, some Chinese-style instrumentals"
        onWeekend {
            +"Weekend sleep-in, some lazy songs"
            +"Weekend shopping, songs for a good mood"
            +"Weekend deep clean, some groovy music to move to"
        }
    }
    during(NOON) {
        +"Lunch break, a few quiet slow songs to unwind"
        +"Lunchtime, some relaxed Bossa Nova"
        +"Half an hour at noon, some slow tunes to zone out"
        +"A quick nap at my desk, some soft white noise"
        +"Walking off lunch, a couple of easy city folk songs"
        +"Soothing cello and acoustic guitar for an afternoon nap"
        +"Loosen up halfway through the day with some lazy French tunes"
        +"Warm sunshine, some sweet but light easy listening"
        +"Can't decide what's for lunch, some appetizing songs first"
        +"Waiting for takeout at noon, something easy"
        +"Twenty-minute lunch break, quick-relaxing easy listening"
        +"Eating lunch alone, some songs that feel like company"
        +"Too full after lunch, some slow jazz"
        +"Out in the noon sun, some warm folk songs"
        +"Before a midday nap, some soft piano"
        +"Lunch queue at the canteen, some upbeat pop"
        +"Groggy after a nap, something gently energizing"
        +"Harsh noon sun, some cool electronic music"
        +"Ten-minute walk at noon, some light tunes"
        +"Some storytelling folk for the lunch break"
        +"Cooking lunch, some homey oldies"
        +"Zoning out at noon, some lyric-free ambient music"
        +"Leaning back at noon, some lazy R&B"
        +"Some romantic French songs with lunch"
        +"Midday break, some light Japanese pop"
        +"Want a change of mood at noon, some bright indie pop"
        +"Resting my eyes at noon, some soft nature sounds"
        +"Half an hour at lunch, a few timeless oldies"
        +"A bit tired at noon, some no-brainer easy songs"
        +"Tea after lunch, some guqin or pipa"
        +"Quiet at home at noon, some low-volume background music"
        +"Reading a few pages at noon, some gentle instrumentals"
        +"Soothing cello before a lunch nap"
        +"Zoning out alone at noon, some chill Lo-Fi"
        +"After-lunch stroll, some light Latin tunes"
        +"Some nostalgic Mandopop classics at noon"
        +"Running errands at noon, something upbeat for the way"
        +"After a nap, some light funk to wake up"
        +"Eyes tired from scrolling, eyes closed for some instrumentals"
        +"Midday change of mood, some cheerful old-school jazz"
    }
    during(AFTERNOON) {
        +"Afternoon tea time, some sweet and comfy indie pop"
        +"A little drowsy in the afternoon, some refreshing beats"
        +"Warm folk for spacing out in the afternoon sun"
        +"Beat the 3 p.m. slump with some catchy retro funk"
        +"Brewing tea, with some textured modern jazz piano"
        +"Japanese mellow instrumentals for slanting sunlight"
        +"A little sugar for the afternoon, some upbeat sweet songs"
        +"Ten minutes by the window, music as gentle as wind in the leaves"
        +"Chores this afternoon, some oldies to sing along to"
        +"Sitting by a café window this afternoon, the right BGM"
        +"Afternoon walk, some easy city folk songs"
        +"Sunny afternoon, warm acoustic guitar"
        +"Sleepy afternoon, some soft little tunes"
        +"Afternoon tea and dessert, some sweet French pop"
        +"Studied all afternoon, a few upbeat songs to take a break"
        +"Gray afternoon, some quiet atmospheric songs"
        +"Four o'clock, something to perk me up"
        +"Reading at home this afternoon, some soothing piano"
        +"Driving out this afternoon, some upbeat Western pop"
        +"Painting this afternoon, some colorful instrumentals"
        +"Slacking off this afternoon, some lazy jazz"
        +"By the window this afternoon, some quiet Japanese folk"
        +"Bored this afternoon, some quirky retro pop"
        +"Sunlight slanting into the room, with some gentle R&B"
        +"Afternoon workout, songs with a strong beat"
        +"Want to zone out this afternoon, some post-rock"
        +"Room's clean, some comfy Lo-Fi as a reward"
        +"Don't fight the afternoon slump, some breezy City Pop"
        +"Chatting with family this afternoon, oldies everyone loves"
        +"Missing the sea this afternoon, something with an ocean breeze"
        +"Cycling this afternoon, some upbeat indie rock"
        +"Baking cookies this afternoon, some sweet songs"
        +"Back from a swim, some cool electronic music"
        +"Before an afternoon movie, a few classic soundtracks"
        +"Curled up under a blanket this afternoon, some lazy songs"
        +"Brushing the cat this afternoon, some soft music"
        +"Out taking photos this afternoon, songs with a film-camera feel"
        +"Windy afternoon, some powerful songs"
        +"Fishing by the river this afternoon, some easygoing folk"
        onWeekend {
            +"Weekend afternoon on the couch, some lazy songs"
            +"Weekend picnic in the park, some easy acoustic guitar"
            +"Weekend afternoon at a bookstore, some quiet jazz"
        }
    }
    during(DUSK) {
        +"On the way home, some relaxed songs to shake off the day"
        +"Sunset hour, romantic melodies for the evening glow"
        +"Evening breeze, some atmospheric City Pop"
        +"Crowded subway or bus, headphones on to block out the noise"
        +"Driving home, some breezy Western pop"
        +"As it gets dark, some warm slow songs"
        +"Shake off the day with gentle folk that slows me down"
        +"Dinner time, some warm and homey background music"
        +"Orange sunset outside the window, a cinematic soundtrack"
        +"Finally off work, some songs to celebrate freedom"
        +"Evening run, songs with a strong beat"
        +"Cooking dinner, some romantic jazz"
        +"Beautiful evening sky, songs worthy of it"
        +"Stuck in traffic on the way home, songs to keep me calm"
        +"Walking home from school, some youthful songs"
        +"Riverside walk at dusk, some soothing folk"
        +"Out for a walk after dinner, some easy city pop"
        +"A bit wistful at dusk, some gentle slow songs"
        +"Cycling home at dusk, indie pop that feels like the wind"
        +"Gym after work, some fat-burning beats"
        +"At sunset, some retro pop with a film-camera feel"
        +"In the kitchen at dusk, some oldies to hum along to"
        +"Want to switch off completely after work, some post-rock"
        +"Dinner with family, some warm, easy background music"
        +"Lovely rooftop at dusk, some lazy R&B"
        +"Packed subway after work, electronic music to block the noise"
        +"Driving into the sunset, some road-trip rock"
        +"Walking the dog at dusk, some cute upbeat songs"
        +"Doing the dishes after dinner, some cheerful disco"
        +"Off work, some jazz for a drink"
        +"Heavy rain this evening, some quiet songs"
        +"Seaside at dusk, some Bossa Nova"
        +"Meeting friends after work, something happy for the way"
        +"After-dinner stroll, some slow-tempo songs"
        +"City lights coming on at dusk, some neon synth-pop"
        +"Shooting hoops after school, some fired-up songs"
        +"Some storytelling folk on the way home from work"
        +"Gorgeous sunset glow, a few romantic French songs"
        +"A bit tired at dusk, some gentle female vocals"
        +"Grocery run at dusk, songs with an everyday feel"
        onWeekend {
            +"Weekend date at dusk, some sweet songs for the way"
            +"Weekend dinner out, something upbeat for the way"
        }
    }
    during(EVENING) {
        +"Lovely night tonight, some mellow slow songs"
        +"Healing music for lying in bed after a shower"
        +"Reading at night, some quiet, warm piano"
        +"Lights dimmed, some lazy, alluring Neo-Soul"
        +"A night to myself, some calm bedroom pop"
        +"Skincare before bed, some minimalist ambient music"
        +"Late at night, a couple of heartfelt Mandopop ballads"
        +"A timeless record to loop while curled up on the couch"
        +"Big lights off, let airy ambient fill the room"
        +"Done for the day, a few relaxing songs for a break"
        +"On the balcony tonight, some lazy jazz"
        +"Home alone tonight, some songs that feel like company"
        +"Want a cinematic score tonight"
        +"Night run, electronic music with a strong beat"
        +"Some soft folk before bed"
        +"Feeling emo tonight, a few gentle slow songs"
        +"Before gaming tonight, some hype electronic music"
        +"Quiet city at night, some City Pop"
        +"Hot bath tonight, some stress-relief songs"
        +"A little drink tonight, some mellow R&B"
        +"Reading before bed, some unobtrusive instrumentals"
        +"Journaling tonight, some gentle Japanese songs"
        +"Beautiful moon tonight, some romantic songs"
        +"Driving home tonight, a few quiet slow songs"
        +"Tidying up tonight, some upbeat songs"
        +"Unwinding before bed, some soothing cello"
        +"Missing old friends tonight, some nostalgic songs"
        +"Watching the rain at home tonight, some quiet ambient music"
        +"Most inspired at night, instrumentals for creating"
        +"Some warm Mandopop oldies before bed"
        +"Want to be quiet tonight, some minimal piano"
        +"Yoga tonight, some ethereal music"
        +"The night is just right, some jazz vocalists"
        +"Video call with friends tonight, easy background music"
        +"Feeling let down tonight, some songs to give me strength"
        +"Ten minutes before bed, songs to help me put the phone down"
        +"Night walk with city views, some city folk"
        +"Long night ahead, some timeless post-rock"
        +"Heading out for a late-night snack, something lively"
        +"Face mask on tonight, some easy songs"
        onWeekend {
            +"Weekend night, not sleeping early, some party songs"
            +"Weekend movie night at home, classic film soundtracks first"
            +"Weekend night with friends, something lively"
        }
    }
    during(LATE_NIGHT) {
        +"Still up late at night? Some calming serenades to help you sleep"
        +"Late-night special, gentle slow songs that understand"
        +"Can't sleep, some lyric-free white noise or ambient"
        +"2:30 a.m., solo piano to listen to alone"
        +"Racing thoughts? Minimal ambient to slow the heart rate"
        +"Night owl club, some trippy, cool electronica"
        +"The whole city is asleep, warm acoustic guitar for the late hours"
        +"Can't fall asleep, dreamy melodies soft as a lullaby"
        +"Woke up in the middle of the night, some soft piano"
        +"Quiet streets in the small hours, some cool City Pop"
        +"Thinking alone late at night, some post-rock"
        +"Driving home late at night, electronic music for night roads"
        +"Just off the night shift, some relaxing songs"
        +"Can't sleep, want some gentle female vocals"
        +"Emo late at night, Mandopop ballads that get me"
        +"3 a.m., ambient that feels like a dream"
        +"Writing late at night, lyric-free music for typing"
        +"Hungry late at night and cooking noodles, something homey"
        +"Can't sleep in the middle of the night, some quiet folk"
        +"Gaming late at night, low-volume electronic music"
        +"Finished a movie in the small hours, scores that linger"
        +"Late-night feelings need an outlet, some cathartic rock"
        +"Drawing late into the night, some atmospheric Lo-Fi"
        +"Rain late at night, some quiet piano"
        +"Missing someone late at night, songs about longing"
        +"Sleepless night, oldies to keep me company"
        +"4 a.m., almost dawn, songs full of hope"
        +"In a taxi late at night, some lazy R&B"
        +"The convenience store in the small hours, songs for night owls"
        +"A drink alone late at night, some mellow jazz"
        +"Woke up at night, easy listening to fall back asleep"
        +"Want to switch off late at night, some minimalism"
        +"Third hour of staying up, some energizing funk"
        +"Late at night, songs with poetic lyrics"
        +"Insomnia in the small hours, ambient nature sounds"
        +"Late-night radio vibes, some old-school love songs"
        +"Binged a show late at night and can't sleep, some quiet songs"
        +"Just got home in the small hours, songs to shake off the day"
        +"Watching streetlights late at night, lonely city folk"
        +"Want to chat late at night but nobody's online, songs for company"
    }
}

/** 场景与生活。 */
private fun scenePool() = aiWelcomePool(SCENE) {
    +"What should I listen to on a rainy day?"
    +"Slow songs for a walk and a clear head"
    +"Workout songs with a strong, on-beat rhythm"
    +"What's good to play on a road trip?"
    +"Songs I'd want to hum along to in the shower"
    +"An upbeat playlist to loop while doing chores"
    +"Songs for a solo walk by the sea"
    +"Background music for a tipsy night at a little bar"
    +"Easy acoustic guitar for camping or a picnic"
    +"Light tunes for cycling down a tree-lined road"
    +"Comforting melodies to ease commute stress"
    +"Ethereal nature sounds for yoga or meditation"
    +"Songs for gazing out the window on a long flight or train"
    +"Headphone songs for wandering a supermarket alone"
    +"Bright melodies for clear skies after rain"
    +"Atmospheric songs for reading alone on a cloudy day"
    +"Love songs to set a romantic mood on a date"
    +"Headphone songs for traveling alone in a new city"
    +"Open, expansive music for hiking"
    +"Hype BGM for gaming with friends"
    +"Stress-relief melodies for a hot spring or a hot bath"
    +"Upbeat songs for packing up and moving"
    +"Painting walls at home, songs that keep me going"
    +"Cooking songs with a rhythm I can chop along to"
    +"Baking a cake, some sweet songs"
    +"Friends over for dinner, lively but not loud background music"
    +"Cheerful songs for a birthday party"
    +"Gentle love songs for wedding background music"
    +"Classic oldies to enjoy with my parents"
    +"Car songs both kids and adults will love"
    +"Lazy music for cat owners at home"
    +"Upbeat songs for walking the dog"
    +"Powerful songs for lifting at the gym"
    +"Cool electronic music to relax after swimming"
    +"Road-trip rock for a long bike ride"
    +"Super-fast songs for jump rope or HIIT"
    +"Soothing instrumentals for stretching"
    +"Energizing songs for a badminton break"
    +"Fired-up sports anthems before the big game"
    +"Nostalgic songs for sorting through old photos"
    +"Gentle folk for writing letters or a diary"
    +"Classical music for oil painting"
    +"Guqin pieces for calligraphy practice"
    +"Easy tunes for knitting"
    +"Unobtrusive background music for LEGO or puzzles"
    +"Disco that keeps me moving while cleaning"
    +"Upbeat pop for doing laundry"
    +"Pastoral folk for gardening"
    +"Island vacation, reggae with an ocean breeze"
    +"Melodic music for a boat ride on the river"
    +"Flowing songs for watching scenery from a fast train"
    +"Expansive road music for a desert drive"
    +"Grand folk songs for a trip to the grasslands"
    +"Music with a water-town charm for an old village stroll"
    +"Before a Tokyo trip, some Japanese city pop"
    +"Before a Paris trip, some French chanson to warm up"
    +"Relaxing music while waiting at the airport"
    +"Upbeat songs to pass time in a long queue"
    +"Electronic music for the subway with noise-canceling on"
    +"Writing at a café, jazz with a city feel"
    +"Quiet music for browsing a bookstore"
    +"Minimal music for wandering an art museum"
    +"Elegant classical pieces for tea in the garden"
    +"Exam's over, some cathartic songs to celebrate"
    +"First day in a new home, some warm songs"
    +"Before karaoke with friends, popular songs to sing together"
    +"Alone in the car, songs I want to belt out"
    +"Singing bowls and nature sounds for meditation"
    +"Soaking my feet, some soothing songs"
    +"Zen music for a walk in the mountains or a temple"
    +"Rainy day at home, some warm jazz"
    +"Windy weather, some powerful songs"
    +"Cloudy mood, some quiet British folk"
    +"Out on a sunny day, some bright pop"
    +"Foggy weather, some hazy dream pop"
    +"Thunderstorm at home, dramatic score music"
    +"Soft jazz to read along to"
    +"Japanese ambient music to paint along to"
    +"Upbeat songs for vlog background music"
    +"Electronic music for inspiration while editing videos"
    +"Low-key background music for streaming"
    +"Songs with rich bass to test a car stereo"
    +"Happy songs for an outdoor barbecue"
    +"Surf rock for a breezy day at the beach"
    +"Epic scores for a sea of clouds from a summit"
    +"Rugged rock for a motorcycle ride"
    +"Opera or oldies to enjoy with grandparents"
    +"Gentle nursery songs or lullabies for a baby"
    +"Cheerful kids' songs for a family outing"
    +"Romantic songs for cooking with my partner"
    +"Electronic music for online gaming with friends"
    +"Bittersweet songs for saying goodbye to a friend"
    +"Graduation-season songs full of memories"
    +"Songs for the day of a breakup"
    +"Retro songs for an old-town street stroll"
    +"Songs for riding the bus around the city"
    +"Light tunes for grocery shopping"
    +"Warm-up songs on the way to the gym"
    +"Chinese-style music to sip tea slowly to"
    +"Upbeat songs for a bike-share ride through the streets"
    +"Songs to stay alert on a long drive"
    +"Campfire sing-along songs"
    +"Steady-tempo songs for an easy jog"
    +"Lively songs for square dancing"
    +"Classic hits to warm up at karaoke"
    +"Easy songs to reward myself after a big clean"
    +"Lazy jazz for a bubble bath"
    +"Easy background music for board games"
    +"Piano pieces as a backdrop for reading poetry"
    +"Post-rock for watching the rain"
    +"Tired from work or study, ten minutes of songs to rest"
    during(EVENING, LATE_NIGHT) {
        +"Slow songs for listening alone late at night"
        +"Late-night zoning out, some gentle healing tunes"
        +"Soft lullabies for counting sheep when I can't sleep"
        +"Whispery songs to sort out my thoughts before bed"
        +"Immersive electronica for a night highway drive"
        +"A few sleepy piano pieces for bedtime"
        +"Sitting in the car late at night before going upstairs, quiet songs"
        +"Coming home late from overtime, some comforting songs"
        +"Sleepless night, white noise like falling rain"
        +"Finished a book late at night, instrumentals that linger"
        +"Ten minutes for myself before bed, some quiet songs"
    }
    during(DUSK, EVENING) {
        +"City music for the rooftop night breeze and skyline"
        +"Light tunes for cycling in the evening breeze"
        +"Camping with friends tonight, what to play by the fire?"
        +"Night market food crawl tonight, something lively"
        +"Before a gig tonight, some rock to warm up"
        +"Live jazz for a little bar tonight"
        +"Night run by the river, songs with a strong beat"
    }
    during(AFTERNOON, DUSK) {
        +"Youthful songs for a sunset jog around the school track"
        +"Grocery shopping after work, something easy"
        +"Gentle folk for watching the sunset"
    }
    during(DAWN, MORNING) {
        +"A fresh playlist for morning light filling the room"
        +"Driving electronica for a morning run"
        +"Making breakfast, some sunny songs"
    }
    during(NOON, AFTERNOON) {
        +"Slow music for napping in the afternoon sun on the balcony"
        +"Lying on the grass in the park this afternoon, easy folk"
        +"An afternoon café, some lazy Bossa Nova"
    }
    inSeason(WINTER) {
        +"Snowy day under the covers, some heartwarming songs"
        +"Hot pot on a winter day, something lively"
        +"Cozy in a heated room in winter, some lazy jazz"
        +"Walking in the snow, music that feels like a snowy scene"
    }
    inSeason(SUMMER) {
        +"Summer at the beach, some cool surf rock"
        +"Summer bike ride, songs that fizz like soda"
        during(NOON, AFTERNOON) { +"Watermelon on a midsummer afternoon, something refreshing" }
        during(DUSK, EVENING) { +"Cooling off on a summer night, nostalgic oldies" }
    }
    inSeason(AUTUMN) {
        +"Walking on autumn leaves, some gentle folk"
        +"Autumn wind is here, some slightly melancholy jazz"
    }
    inSeason(SPRING) {
        +"Spring outing in the countryside, some bright songs"
        +"Drizzly spring days, some gentle piano"
        +"Spring blossoms, some fresh Japanese folk"
    }
    onWeekend {
        +"Weekend gaming at home, some hype BGM"
        +"Weekend camping trip, something easy for the drive"
        +"Weekend family outing, car songs everyone loves"
        +"Weekend exhibition, artsy songs for the way"
    }
}

/** 心情与情绪。 */
private fun moodPool() = aiWelcomePool(MOOD) {
    +"Feeling irritable, songs that calm me down instantly"
    +"Hurt and can't cry, some slow songs that get me"
    +"So happy today! Super sunny, joyful songs"
    +"Feeling emo, some gentle songs to hug myself"
    +"Under pressure, music that lets me breathe deeply"
    +"Feeling lonely, songs like a friend humming beside me"
    +"Healing songs that make life feel hopeful"
    +"Suddenly nostalgic, old melodies that bring back my youth"
    +"Feeling empty, warm slow songs that keep me company"
    +"Need some security, songs with thick, warm arrangements"
    +"Life feels flat, songs that make my heart skip"
    +"Feeling lost, some motivational songs to lift me up"
    +"Need to let it all out, some cathartic hard rock"
    +"Exhausted, songs that wrap around me like warm water"
    +"Want my youthful spirit back, some sincere songs"
    +"Feeling restless, some cool, still instrumentals"
    +"Sweet songs that make me smile"
    +"Calm mood, songs that flow like water"
    +"Feeling mysterious, some dark, obscure music"
    +"Drowning in daily chores, some vast, open songs"
    +"Songs that make me reflect on life"
    +"Suddenly missing someone, some deeply emotional love songs"
    +"Want some romance, songs like falling into a pink bubble"
    +"Pure relaxation, music with no pressure at all"
    +"Low on energy, catchy songs that aren't noisy"
    +"Letting go of the past, gentle songs of making peace"
    +"Lonely but not sad, music with a cool beauty"
    +"Earthy folk full of life"
    +"Songs that make me want to run and hug the world"
    +"Warm songs that say tomorrow is worth looking forward to"
    +"Want a dreamy, tipsy feeling, romantic ambient music"
    +"Steady, quiet, heartfelt songs to keep me company"
    +"Songs that make me feel understood by the whole world"
    +"Need positive energy, some uplifting sing-along anthems"
    +"Just got scolded by my boss, songs to cool off"
    +"Got praised today, songs to match the good mood"
    +"Just got dumped, breakup songs that aren't too bitter"
    +"Broke up long ago but still think of it, songs about moving on"
    +"Secret crush, songs about feelings I can't say"
    +"Just fell in love, sickly sweet love songs"
    +"Long-distance and missing you, songs about longing"
    +"Had a fight, songs to cool me down"
    +"Homesick, songs that taste like home"
    +"Making it on my own, songs to cheer myself on"
    +"Feeling like a failure, gentle songs that catch me"
    +"Bombed an exam, some comforting songs"
    +"Finally got the offer, celebration songs"
    +"Just quit my job, free and easy songs"
    +"Anxious about the future, songs that steady me"
    +"Don't want to do anything today, songs that let me lie flat"
    +"Feeling socially drained, songs for quiet alone time"
    +"Nobody understands me, songs for loners"
    +"A little heartache, slightly bittersweet love songs"
    +"Randomly happy, bouncy songs"
    +"Need a good cry, some tearjerkers"
    +"Done crying, songs to help me pick myself up"
    +"Mind's a mess, instrumentals to sort out my feelings"
    +"Kind of angry, punk to let it all out"
    +"Life is pushing me along, songs that make me pause"
    +"Missing my childhood, some throwback songs"
    +"Remembering college days, some campus folk"
    +"Missing someone who's gone, gentle farewell songs"
    +"Envious of others' lives, songs to be at ease as myself"
    +"Felt brave today, some fired-up songs to match"
    +"Nervous, songs to help me breathe"
    +"Mood like a cloudy day, gentle songs that aren't glaring"
    +"Mood like a sunny day, some bright pop"
    +"Want to fall in love, some sweet love songs"
    +"Single and happy, independent, carefree songs"
    +"Healed by friends, songs about friendship"
    +"Want to make up with a friend, songs about apology"
    +"Want to escape the city, free road-trip folk"
    +"Everything's good, songs about little joys"
    +"Feeling hollow, songs to fill me up"
    +"Feeling sentimental, some artsy songs to match"
    +"An ordinary day, songs celebrating ordinary life"
    +"Feeling let down, songs with backbone"
    +"Want a sense of ceremony, some grand classical music"
    +"Missing who I used to be, songs about growing up"
    +"Burned out, songs to reignite my passion"
    +"Something's weighing on me, songs about letting go"
    +"Want to be cool today, some icy electronic music"
    +"Feeling cuddly today, some cute songs"
    +"Want to go all out today, some battle anthems"
    +"Suddenly want to travel, songs about faraway places"
    +"Complicated feelings, songs with many layers"
    +"Need encouragement, gentle pep-talk songs"
    +"So-so mood, just play something comfortable"
    +"The world feels loud, some quiet songs"
    +"Want my focus back, some grounding instrumentals"
    +"Feeling rebellious, some untamed rock"
    +"Feeling loved, some warm love songs"
    +"Want to confess my feelings, songs to give me courage"
    +"Need some hope, songs like a beam of light"
    +"At a crossroads, songs about choices"
    +"A stranger was kind to me today, some gentle songs"
    +"Proud of myself, confident songs"
    +"Going in circles, songs with forward motion"
    +"Feeling down today, sad but beautiful songs"
    +"Want light, floaty happiness, some dream pop"
    +"Heavy heart, some solemn cello"
    +"Missing a certain city, songs about cities"
    +"Want to feel proud today, songs of victory"
    +"At peace, some zen instrumentals"
    +"Eating well even alone, some warm songs"
    +"Too tired to talk, songs that need no reply"
    +"Need courage for tomorrow, some resolute songs"
    +"Feeling jealous, songs to stop comparing"
    +"Want to be sad quietly for a while, songs to keep me company"
    +"Want a laugh, some funny songs"
    +"Want something that feels like an anime battle"
    +"Want to be moved, live performances that hit hard"
    +"Life feels short, songs about seizing the day"
    +"Tired but have to keep going, gentle songs of perseverance"
    +"Feeling low, but want upbeat contrast"
    +"Want to shake off my worries, some earworms"
    +"Want to be understood, songs about ordinary people"
    +"Disappointed by a relationship, clear-headed songs"
    +"Feeling blue today, some bluesy songs"
    +"Feeling pink today, some sweet songs"
    +"Need strength, songs of female empowerment"
    +"Want tenderness, some low, gentle male vocals"
    +"Unhappy but don't want sad songs, something upbeat and healing"
    +"Happy but want it quiet, some gentle songs"
    +"Want a thrill, adrenaline-pumping songs"
    +"Great mood, want to dance, some dance tracks"
    +"Starting over, songs about new beginnings"
    +"Feeling small, some grand orchestral scores"
    +"Need a hug, songs that feel like one"
    +"Heart-tired, gentle songs without preaching"
    +"Feeling happy, songs about being there for each other"
    +"Missing mom today, songs about family"
    +"Missing dad, songs about fathers"
    +"Missing old classmates, songs about youth"
    +"Really want songs I can sing out loud today"
    +"Words I can't say, songs that say them for me"
    during(EVENING, LATE_NIGHT) { +"Restrained, low-voiced songs to process feelings late at night" }
}

/** 流派、语种与年代。 */
private fun genrePool() = aiWelcomePool(GENRE) {
    +"Some underrated but great City Pop"
    +"Relaxed Bossa Nova or light jazz"
    +"Retro 80s and 90s Hong Kong classics"
    +"Indie folk that isn't clichéd"
    +"Good post-rock or ambient instrumentals"
    +"Beginner-friendly classical piano"
    +"Mellow R&B or Neo-Soul"
    +"Classic rock with killer guitar solos"
    +"Fresh, lesser-known Japanese female vocalists"
    +"Chill, unobtrusive Lo-Fi Beats"
    +"Classic Mandopop from the golden 2000s"
    +"Melodic, finely arranged Britpop"
    +"Comfy City Pop from the new generation"
    +"Atmospheric shoegaze"
    +"Pure vintage jazz on vinyl"
    +"Synthwave with retro synth tones"
    +"Romantic, languid French chanson"
    +"Catchy but classy retro funk and disco"
    +"Ambient world music with traditional instruments"
    +"Woody fingerstyle guitar instrumentals"
    +"Pure Nordic post-rock or ethereal music"
    +"Very melodic indie pop"
    +"Easy-on-the-ears chillwave or light electronica"
    +"Timeless Chinese folk with strummed acoustic guitar"
    +"Dramatic, cinematic neo-classical instrumentals"
    +"Vocal records steeped in the blues"
    +"Romantic modern cello and violin duets"
    +"Groovy nu-disco to dance to"
    +"Warm, laid-back island reggae"
    +"Dream pop for headphones"
    +"Psychedelic 60s rock"
    +"Stunning tracks by lesser-known female songwriters"
    +"Progressive but listenable math rock"
    +"Grand orchestral film soundtracks"
    +"Bouncy retro soul"
    +"Airy vocal jazz"
    +"Exotic South American tango or flamenco guitar"
    +"80s pop with warm synths"
    +"Slow funk with smooth drums and catchy bass"
    +"Japanese city folk for strolling"
    +"Artsy chamber music or minimalist modern music"
    +"Poetic songs from Taiwan's campus folk era"
    +"Wild, lively bluegrass"
    +"Beautiful harp or handpan music"
    +"Rich choral songs with gospel color"
    +"Futuristic ambient electronica"
    +"Classic Motown oldies"
    +"Swing jazz classics"
    +"Nordic indie with a cool, icy feel"
    +"Unforgettable Nordic folk-pop"
    +"Classic Cantopop from its golden age"
    +"Great Cantonese songs from recent years"
    +"Old Hokkien songs full of warmth"
    +"Songs in Hakka or other Chinese dialects"
    +"Classic Japanese City Pop"
    +"Great anime theme songs"
    +"Hidden gems from Japanese indie bands"
    +"Great Korean indie music"
    +"Classic K-Pop dance tracks"
    +"Beautiful K-drama ballad OSTs"
    +"Great Thai pop"
    +"Vietnamese pop, a different side of Southeast Asia"
    +"Upbeat Bollywood-style songs"
    +"Great Spanish-language pop"
    +"Classic Brazilian songs in Portuguese"
    +"French pop beyond chanson"
    +"German rock or German electronica"
    +"Classic Italian oldies"
    +"Russian songs with cold, stark melodies"
    +"Icelandic artists, cold and ethereal"
    +"Folk songs sung in Nordic languages"
    +"Irish folk with pipes and fiddle"
    +"Afrobeat rhythms"
    +"Latin dance music, salsa or bachata"
    +"Mongolian-style music with throat singing or morin khuur"
    +"Xinjiang-style folk music"
    +"Ethereal Tibetan-style songs"
    +"Chinese-style (guofeng) songs"
    +"Pop songs blended with Chinese opera vocals"
    +"Modern works arranged for Chinese instruments"
    +"Classic 70s rock"
    +"80s Western pop hits"
    +"90s Britpop"
    +"Early 2000s Western pop"
    +"2010s indie pop"
    +"Classic 90s Mandopop love songs"
    +"80s Taiwanese campus folk songs"
    +"Old Shanghai songs from the 1930s and 40s"
    +"50s doo-wop"
    +"60s soul"
    +"Classic 90s East and West Coast hip-hop"
    +"Boom bap rap"
    +"Good Chinese-language rap"
    +"Trap songs"
    +"Jazz rap with jazz samples"
    +"Trip-hop, dark and alluring"
    +"Drum and bass"
    +"House tracks to dance to"
    +"Techno, cold and repetitive"
    +"Future bass"
    +"Vaporwave"
    +"Chillwave like a faded old photo"
    +"IDM"
    +"Experimental glitch electronica"
    +"Downtempo electronica"
    +"Ambient techno"
    +"Industrial rock"
    +"Heavy metal for beginners"
    +"Progressive rock epics"
    +"Post-punk revival bands"
    +"Garage rock, raw but punchy"
    +"Pop punk, youthful and fired up"
    +"Emo"
    +"Grunge"
    +"Britpop anthems"
    +"Folk rock"
    +"Alt-country"
    +"Classic country music"
    +"Southern rock"
    +"Blues rock"
    +"Authentic Delta blues"
    +"Gospel music"
    +"Funk with a nasty bass line"
    +"Acid jazz"
    +"Smooth jazz"
    +"Bebop, jazz at full speed"
    +"Cool jazz"
    +"An introduction to free jazz"
    +"Fusion jazz"
    +"Big band jazz"
    +"Latin jazz"
    +"Afro-Cuban music"
    +"Ska with a cheerful offbeat"
    +"Dub, a world of echo and bass"
    +"Dancehall"
    +"J-Rock"
    +"Signature tracks from visual kei bands"
    +"Shibuya-kei"
    +"Japanese enka"
    +"Mandopop R&B"
    +"Chinese indie rock"
    +"Chinese post-rock"
    +"Chinese electronic music"
    +"New voices in Chinese folk"
    +"Songs by Taiwanese indie bands"
    +"Classics of mainland Chinese rock"
    +"Northwest Chinese-style rock"
    +"Neo-classical"
    +"Classics from the Baroque period"
    +"Romantic-era piano pieces"
    +"Impressionist music with a hazy feel"
    +"Minimalist works"
    +"Choral works, sacred music welcome"
    +"An introduction to opera arias"
    +"Classic musical theater numbers"
    +"Symphonies for beginners"
    +"String quartets"
    +"Classical guitar pieces"
    +"Famous traditional Chinese pieces"
    +"Guzheng pieces"
    +"Famous erhu pieces"
    +"Dizi and xiao flute pieces"
    +"Great music from video game soundtracks"
    +"Animated film scores"
    +"Classic TV theme songs"
    +"Lo-Fi hip-hop"
    +"Bedroom pop"
    +"Hyperpop, the loudest and newest pop"
    +"Art pop"
    +"Baroque pop"
    +"Chamber pop"
    +"Sophisti-pop"
    +"Yacht rock"
    +"AOR"
    +"Soft rock"
    +"Power ballads"
    +"Space rock"
    +"Krautrock"
    +"Noise pop"
    +"Slowcore"
    +"Epic post-rock pieces"
    +"Portuguese fado"
    +"French touch electronica"
    +"Classic K-Pop girl group songs"
    +"New Mandopop from recent years"
    +"Neo-psychedelia"
    +"Indie folk"
    +"Electro swing"
    +"Breakbeat"
    +"UK garage"
    +"New age music"
    +"Celtic music"
    +"Cumbia"
    +"K-R&B"
    +"Reggaeton classics"
    +"Latin trap"
    +"Spanish indie pop"
    +"Mexican regional music, mariachi or norteño"
    +"Argentine rock classics"
    +"Nueva trova and Latin American protest songs"
    +"Modern flamenco fusion"
    +"Bolero love songs"
    +"Colombian vallenato"
    +"Rock en español from the 80s and 90s"
}

/** 相似艺人：每位只出现一次，句子引导去找风格相近的其他人；西语艺人占比更高。 */
private fun artistPool() = aiWelcomePool(ARTIST) {
    // 西语与拉丁
    artist("Rosalía", "Love Rosalía's new flamenco? Who else sounds similar?")
    artist("Bad Bunny", "Into Bad Bunny's reggaeton? Recommend other similar artists")
    artist("J Balvin", "Like J Balvin's colorful reggaeton? Who else is like that?")
    artist("Shakira", "Love Shakira's Latin pop? Recommend similar singers")
    artist("Juanes", "Like Juanes' Latin rock? Who else sounds similar?")
    artist("Maná", "Love Maná's rock ballads? Recommend other similar bands")
    artist("Soda Stereo", "Love Soda Stereo's Argentine rock? Other bands like them?")
    artist("Gustavo Cerati", "Like Gustavo Cerati's solo work? Who else is similar?")
    artist("Café Tacvba", "Love Café Tacvba's eclectic rock? Recommend similar bands")
    artist("Los Fabulosos Cadillacs", "Like Los Fabulosos Cadillacs' ska? Other similar bands?")
    artist("Caifanes", "Love Caifanes' dark Mexican rock? Who else is similar?")
    artist("Héroes del Silencio", "Like Héroes del Silencio? Recommend other similar bands")
    artist("Enrique Iglesias", "Love Enrique Iglesias' Latin pop? Who else sounds similar?")
    artist("Julio Iglesias", "Like Julio Iglesias' romantic ballads? Similar singers?")
    artist("Luis Miguel", "Love Luis Miguel's boleros? Who else sings like that?")
    artist("Alejandro Sanz", "Like Alejandro Sanz's flamenco-pop? Other similar artists?")
    artist("Natalia Lafourcade", "Love Natalia Lafourcade's Latin folk? Who else is similar?")
    artist("Mon Laferte", "Like Mon Laferte's dramatic voice? Recommend similar singers")
    artist("Julieta Venegas", "Love Julieta Venegas' accordion pop? Who else is similar?")
    artist("Jorge Drexler", "Like Jorge Drexler's gentle songwriting? Similar artists?")
    artist("Silvio Rodríguez", "Love Silvio Rodríguez's nueva trova? Who else is like him?")
    artist("Mercedes Sosa", "Like Mercedes Sosa's folk? Recommend other similar voices")
    artist("Violeta Parra", "Love Violeta Parra's Chilean folk? Who else is similar?")
    artist("Víctor Jara", "Like Víctor Jara's protest songs? Other similar songwriters?")
    artist("Chavela Vargas", "Love Chavela Vargas' raw rancheras? Who else sings like that?")
    artist("Lila Downs", "Like Lila Downs' Mexican roots music? Similar artists?")
    artist("Calle 13", "Love Calle 13's urban rap? Recommend other similar artists")
    artist("Karol G", "Like Karol G's reggaeton? Who else sounds similar?")
    artist("Rauw Alejandro", "Love Rauw Alejandro's dance pop? Other artists like him?")
    artist("Ozuna", "Like Ozuna's melodic reggaeton? Who else is similar?")
    artist("Daddy Yankee", "Love Daddy Yankee's classic reggaeton? Similar artists?")
    artist("Marc Anthony", "Like Marc Anthony's salsa? Recommend other similar singers")
    artist("Celia Cruz", "Love Celia Cruz's salsa? Who else has that energy?")
    artist("Héctor Lavoe", "Like Héctor Lavoe's salsa? Recommend similar singers")
    artist("Rubén Blades", "Love Rubén Blades' storytelling salsa? Who else is similar?")
    artist("Juan Luis Guerra", "Like Juan Luis Guerra's merengue and bachata? Similar artists?")
    artist("Romeo Santos", "Love Romeo Santos' bachata? Who else sounds similar?")
    artist("Aventura", "Like Aventura's bachata? Recommend other similar groups")
    artist("Carlos Vives", "Love Carlos Vives' vallenato pop? Who else is similar?")
    artist("Bomba Estéreo", "Like Bomba Estéreo's electro-cumbia? Other similar bands?")
    artist("Kali Uchis", "Love Kali Uchis' dreamy R&B? Who else sounds similar?")
    artist("Zoé", "Like Zoé's psychedelic rock? Recommend other similar bands")
    artist("Vetusta Morla", "Love Vetusta Morla's Spanish indie? Who else is similar?")
    artist("Enrique Bunbury", "Like Enrique Bunbury's solo rock? Other similar artists?")
    artist("Joaquín Sabina", "Love Joaquín Sabina's songwriting? Who else is like him?")
    artist("Joan Manuel Serrat", "Like Joan Manuel Serrat's poetic songs? Similar artists?")
    artist("Camarón de la Isla", "Love Camarón de la Isla's flamenco? Who else sings like that?")
    artist("Buika", "Like Buika's flamenco-jazz voice? Recommend similar singers")
    artist("C. Tangana", "Love C. Tangana's modern Spanish sound? Who else is similar?")
    artist("Peso Pluma", "Like Peso Pluma's corridos? Recommend other similar artists")
    artist("Carin León", "Love Carin León's regional Mexican? Who else sounds similar?")
    artist("Selena", "Like Selena's Tejano pop? Recommend other similar singers")
    artist("Ricky Martin", "Love Ricky Martin's Latin pop? Who else has that energy?")
    artist("Gloria Estefan", "Like Gloria Estefan's Latin pop? Similar artists?")
    artist("Los Ángeles Azules", "Love Los Ángeles Azules' cumbia? Other similar groups?")
    artist("Silvana Estrada", "Like Silvana Estrada's intimate folk? Who else is similar?")
    artist("Ana Tijoux", "Love Ana Tijoux's Spanish-language rap? Similar artists?")
    artist("Babasónicos", "Like Babasónicos' Argentine rock? Who else is similar?")
    artist("Fito Páez", "Love Fito Páez's piano rock? Recommend other similar artists")
    artist("Charly García", "Like Charly García? Who else in Argentine rock is similar?")
    artist("Andrés Calamaro", "Love Andrés Calamaro's rock? Who else sounds similar?")
    artist("Los Prisioneros", "Like Los Prisioneros' Chilean new wave? Similar bands?")
    artist("Jesse & Joy", "Love Jesse & Joy's Mexican pop? Who else sounds similar?")
    artist("Sin Bandera", "Like Sin Bandera's romantic duets? Recommend similar artists")
    artist("Natalia Jiménez", "Love Natalia Jiménez's powerful voice? Similar singers?")
    artist("Morat", "Like Morat's folk-pop? Recommend other similar bands")
    artist("Rubén Rada", "Love Rubén Rada's candombe? Who else is similar?")
    artist("Astor Piazzolla", "Like Astor Piazzolla's nuevo tango? Who else is similar?")
    artist("Paco de Lucía", "Love Paco de Lucía's flamenco guitar? Similar players?")
    artist("Gipsy Kings", "Like the Gipsy Kings' fiery rumba? Who else is similar?")
    artist("Rodrigo y Gabriela", "Love Rodrigo y Gabriela's guitar duo? Similar acts?")
    artist("Buena Vista Social Club", "Like Buena Vista Social Club's Cuban son? Similar artists?")
    // 华语、日本、韩国
    artist("Jay Chou", "Love Jay Chou's Chinese-style pop? Who else is similar?")
    artist("Faye Wong", "Like Faye Wong's ethereal voice? Who else has that air?")
    artist("Teresa Teng", "Love Teresa Teng's sweet ballads? Recommend similar singers")
    artist("Leslie Cheung", "Like Leslie Cheung? What other Cantopop from his era?")
    artist("Eason Chan", "Love Eason Chan's storytelling ballads? Who else is similar?")
    artist("JJ Lin", "Like JJ Lin's ballads? Recommend other similar singers")
    artist("G.E.M.", "Love G.E.M.'s powerful vocals? Who else sounds similar?")
    artist("Beyond", "Like Beyond's Cantonese rock? Recommend other similar bands")
    artist("Anita Mui", "Love Anita Mui's deep voice? Who else sings like her?")
    artist("Mayday", "Like Mayday's anthemic band rock? Other similar bands?")
    artist("Hikaru Utada", "Love Hikaru Utada's R&B pop? Who else sounds similar?")
    artist("Sheena Ringo", "Like Sheena Ringo's wild arrangements? Similar artists?")
    artist("Miyuki Nakajima", "Love Miyuki Nakajima's grand songs? Who else is like her?")
    artist("Koji Tamaki", "Like Koji Tamaki's rich voice? Recommend similar singers")
    artist("Joe Hisaishi", "Love Joe Hisaishi's warm melodies? Similar composers?")
    artist("Ryuichi Sakamoto", "Like Ryuichi Sakamoto's restrained piano? Who else is similar?")
    artist("Tatsuro Yamashita", "Love Tatsuro Yamashita's City Pop? Others from that era?")
    artist("Mariya Takeuchi", "Like Mariya Takeuchi's city pop? Who else is similar?")
    artist("Taeko Ohnuki", "Love Taeko Ohnuki's elegant city pop? Similar singers?")
    artist("Yumi Matsutoya", "Like Yumi Matsutoya's songwriting? Who else is similar?")
    artist("Anri", "Love Anri's bright City Pop? Recommend similar singers")
    artist("Akina Nakamori", "Like Akina Nakamori's Showa pop? Who else is similar?")
    artist("Mr.Children", "Love Mr.Children's pop rock? Other similar Japanese bands?")
    artist("Spitz", "Like Spitz's breezy guitar pop? Who else sounds similar?")
    artist("X Japan", "Love X Japan's visual kei metal? Other similar bands?")
    artist("L'Arc~en~Ciel", "Like L'Arc~en~Ciel's lush rock? Who else is similar?")
    artist("RADWIMPS", "Love RADWIMPS' fiery yet tender rock? Similar bands?")
    artist("Aimer", "Like Aimer's husky voice? Recommend other similar singers")
    artist("LiSA", "Love LiSA's anime anthems? Who else sounds similar?")
    artist("YOASOBI", "Like YOASOBI's story-driven electropop? Similar acts?")
    artist("Kenshi Yonezu", "Love Kenshi Yonezu's songwriting? Who else is similar?")
    artist("King Gnu", "Like King Gnu's genre-mixing rock? Other similar bands?")
    artist("Official HIGE DANdism", "Love Official HIGE DANdism's piano pop rock? Similar bands?")
    artist("Vaundy", "Like Vaundy's shifting styles? Recommend similar young artists")
    artist("Fujii Kaze", "Love Fujii Kaze's piano R&B? Who else sounds similar?")
    artist("Ado", "Like Ado's explosive vocals? Recommend other similar singers")
    artist("ONE OK ROCK", "Love ONE OK ROCK's fired-up rock? Other similar bands?")
    artist("Gen Hoshino", "Like Gen Hoshino's breezy pop? Who else is similar?")
    artist("Suchmos", "Love Suchmos' jazzy funk? Recommend other similar bands")
    artist("Nujabes", "Like Nujabes' jazz hip-hop? Who else produces like that?")
    artist("Yoko Kanno", "Love Yoko Kanno's scores? Recommend similar composers")
    artist("Hiroyuki Sawano", "Like Hiroyuki Sawano's epic scores? Similar composers?")
    artist("Nobuo Uematsu", "Love Nobuo Uematsu's game music? Who else is similar?")
    artist("Koji Kondo", "Like Koji Kondo's game melodies? Recommend similar composers")
    artist("Yoko Shimomura", "Love Yoko Shimomura's game scores? Who else is similar?")
    artist("Yasunori Mitsuda", "Like Yasunori Mitsuda's game music? Similar composers?")
    artist("Perfume", "Love Perfume's electropop? Recommend other similar groups")
    artist("Tokyo Jihen", "Like Tokyo Jihen's band arrangements? Similar bands?")
    artist("Lamp", "Love Lamp's soft pop? Who else sounds similar?")
    artist("cero", "Like cero's urban indie? Recommend other similar Japanese bands")
    artist("toe", "Love toe's math rock? Other similar bands?")
    artist("MONO", "Like MONO's sweeping post-rock? Who else is similar?")
    artist("Ayumi Hamasaki", "Love Ayumi Hamasaki's 2000s pop? Others from that era?")
    artist("Namie Amuro", "Like Namie Amuro's dance pop? Who else sounds similar?")
    artist("Lisa Ono", "Love Lisa Ono's Bossa Nova? Recommend similar singers")
    artist("Hitsujibungaku", "Like Hitsujibungaku's dreamy rock? Other similar bands?")
    artist("YMO", "Love YMO's electropop? Which other pioneers are similar?")
    artist("IU", "Like IU's songs? Recommend similar Korean female singers")
    artist("BIGBANG", "Love BIGBANG? Which other K-pop groups are similar?")
    artist("BTS", "Like BTS? Recommend other groups with a similar style")
    artist("BLACKPINK", "Love BLACKPINK's girl-crush style? Who else is similar?")
    artist("Girls' Generation", "Like Girls' Generation's classics? Others from that era?")
    artist("Red Velvet", "Love Red Velvet's range? Which other groups are similar?")
    artist("NewJeans", "Like NewJeans' light retro feel? Recommend similar groups")
    artist("Taeyeon", "Love Taeyeon's ballad voice? Recommend similar singers")
    artist("Cho Yong-pil", "Like Cho Yong-pil's Korean classics? Who else is similar?")
    artist("HYUKOH", "Love HYUKOH's indie rock? Other similar bands?")
    artist("Zion.T", "Like Zion.T's R&B? Who else sounds similar?")
    artist("Crush", "Love Crush's R&B? Recommend other similar Korean singers")
    artist("DEAN", "Like DEAN's alternative R&B? Who else is similar?")
    artist("Lee Sora", "Love Lee Sora's quiet voice? Recommend similar singers")
    artist("Baek Yerin", "Like Baek Yerin's languid R&B? Who else sounds similar?")
    artist("Epik High", "Love Epik High's rap? Which other groups are similar?")
    artist("SHINee", "Like SHINee's dance tracks? Recommend other similar groups")
    artist("Jannabi", "Love Jannabi's retro band sound? Who else is similar?")
    // 欧美流行
    artist("Taylor Swift", "Like Taylor Swift's folk-era albums? Who else is similar?")
    artist("Adele", "Love Adele's powerful soulful voice? Recommend similar singers")
    artist("Ed Sheeran", "Like Ed Sheeran's one-guitar songs? Who else is similar?")
    artist("Billie Eilish", "Love Billie Eilish's whispery dark pop? Similar artists?")
    artist("Lana Del Rey", "Like Lana Del Rey's vintage Hollywood feel? Who else is similar?")
    artist("Bruno Mars", "Love Bruno Mars' retro funk hits? Recommend similar artists")
    artist("Lauv", "Like Lauv's emotional modern pop? Who else sounds similar?")
    artist("Michael Jackson", "Love Michael Jackson's dance hits? Other similar pop icons?")
    artist("Madonna", "Like Madonna's dance pop? Recommend similar female artists")
    artist("Whitney Houston", "Love Whitney Houston's vocals? Which other divas are similar?")
    artist("Mariah Carey", "Like Mariah Carey's high notes? Similar R&B voices?")
    artist("Celine Dion", "Love Celine Dion's big ballads? Who else sounds similar?")
    artist("Lady Gaga", "Like Lady Gaga's dance pop? Recommend similar artists")
    artist("Rihanna", "Love Rihanna's pop R&B? Which other singers are similar?")
    artist("Beyoncé", "Like Beyoncé's power? Recommend other similar female artists")
    artist("Ariana Grande", "Love Ariana Grande's pop R&B? Who else is similar?")
    artist("Dua Lipa", "Like Dua Lipa's retro dance pop? Recommend similar singers")
    artist("The Weeknd", "Love The Weeknd's retro synth R&B? Who else is similar?")
    artist("Harry Styles", "Like Harry Styles' retro pop rock? Similar artists?")
    artist("Olivia Rodrigo", "Love Olivia Rodrigo's pop punk? Who else sounds similar?")
    artist("Sia", "Like Sia's voice and pop? Recommend similar singers")
    artist("Lorde", "Love Lorde's minimalist pop? Which other songwriters are similar?")
    artist("Robyn", "Like Robyn's electropop? Recommend other similar artists")
    artist("ABBA", "Love ABBA's retro pop? Which other groups are similar?")
    artist("Bee Gees", "Like the Bee Gees' disco? Other bands from that era?")
    artist("Carpenters", "Love the Carpenters' gentle oldies? Who else is similar?")
    artist("Michael Bublé", "Like Michael Bublé's swing? Recommend similar male voices")
    artist("Sam Smith", "Love Sam Smith's soulful pop? Who else is similar?")
    artist("John Mayer", "Like John Mayer's bluesy guitar? Recommend similar artists")
    artist("Jason Mraz", "Love Jason Mraz's breezy songs? Who else is similar?")
    artist("Maroon 5", "Like Maroon 5's funky pop? Recommend other similar bands")
    artist("OneRepublic", "Love OneRepublic's pop rock? Which other bands are similar?")
    artist("Imagine Dragons", "Like Imagine Dragons' anthemic rock? Similar bands?")
    artist("Shawn Mendes", "Love Shawn Mendes' guitar pop? Who else is similar?")
    artist("Charlie Puth", "Like Charlie Puth's pop production? Similar artists?")
    artist("Troye Sivan", "Love Troye Sivan's dreamy pop? Who else is similar?")
    artist("Clairo", "Like Clairo's bedroom pop? Recommend similar songwriters")
    artist("Phoebe Bridgers", "Love Phoebe Bridgers' melancholy folk? Who else is similar?")
    artist("Mitski", "Like Mitski's indie rock? Recommend similar songwriters")
    artist("Laufey", "Love Laufey's jazz pop? Which other singers are similar?")
    artist("Corinne Bailey Rae", "Like Corinne Bailey Rae's soul? Recommend similar voices")
    artist("Jack Johnson", "Love Jack Johnson's surf folk? Who else is similar?")
    artist("Damien Rice", "Like Damien Rice's folk? Recommend similar songwriters")
    artist("Norah Jones", "Love Norah Jones' café jazz? Who else is worth hearing?")
    artist("Keane", "Like Keane's piano rock? Which other bands are similar?")
    artist("Snow Patrol", "Love Snow Patrol's British rock? Recommend similar bands")
    artist("Travis", "Like Travis' gentle Britpop? Who else is similar?")
    artist("Coldplay", "Love early Coldplay's warm, grand sound? Similar bands?")
    // 欧美摇滚与独立
    artist("The Beatles", "Love The Beatles? Which other bands from that era?")
    artist("The Rolling Stones", "Like The Rolling Stones? Recommend similar bands")
    artist("Queen", "Love Queen's flamboyant rock? Which other bands are similar?")
    artist("Led Zeppelin", "Like Led Zeppelin's hard rock? Similar classic bands?")
    artist("Pink Floyd", "Love Pink Floyd's progressive psychedelia? Who else is similar?")
    artist("The Doors", "Like The Doors' psychedelic rock? Other bands from that era?")
    artist("David Bowie", "Love David Bowie's many styles? Similar artists?")
    artist("Nirvana", "Like Nirvana's grunge? Recommend other similar bands")
    artist("Radiohead", "Love Radiohead's alternative rock? Which bands are similar?")
    artist("Oasis", "Like Oasis' sing-along Britpop? Recommend similar bands")
    artist("Blur", "Love Blur's Britpop? Recommend other similar bands")
    artist("The Smiths", "Like The Smiths' indie rock? Who else is similar?")
    artist("The Cure", "Love The Cure's post-punk? Recommend other similar bands")
    artist("Joy Division", "Like Joy Division's cold post-punk? Who else is similar?")
    artist("U2", "Love U2's grand rock? Recommend similar bands")
    artist("Muse", "Like Muse's epic rock? Which other bands are similar?")
    artist("Arctic Monkeys", "Love Arctic Monkeys' indie rock? Similar bands?")
    artist("The Strokes", "Like The Strokes' garage rock? Who else is similar?")
    artist("The Killers", "Love The Killers' synth rock? Recommend similar bands")
    artist("Green Day", "Like Green Day's pop punk? Which other bands are similar?")
    artist("Linkin Park", "Love Linkin Park's nu metal? Recommend similar bands")
    artist("Foo Fighters", "Like Foo Fighters' hard rock? Who else is similar?")
    artist("Red Hot Chili Peppers", "Love Red Hot Chili Peppers' funk rock? Similar bands?")
    artist("Guns N' Roses", "Like Guns N' Roses' hard rock? Other bands from that era?")
    artist("Metallica", "Love Metallica's metal? Which other bands are similar?")
    artist("Bon Jovi", "Like Bon Jovi's pop metal? Recommend similar bands")
    artist("Eagles", "Love the Eagles' country rock? Who else is similar?")
    artist("Fleetwood Mac", "Like Fleetwood Mac's soft rock? Recommend similar bands")
    artist("Dire Straits", "Love Dire Straits' guitar? Which other bands are similar?")
    artist("Bob Dylan", "Like Bob Dylan's folk? Recommend other similar songwriters")
    artist("Neil Young", "Love Neil Young's folk rock? Who else is similar?")
    artist("Simon & Garfunkel", "Like Simon & Garfunkel's harmonies? Similar duos?")
    artist("Leonard Cohen", "Love Leonard Cohen's deep poetry? Who else is similar?")
    artist("Tom Waits", "Like Tom Waits' gravelly, odd songs? Similar artists?")
    artist("Bruce Springsteen", "Love Bruce Springsteen's heartland rock? Who else is similar?")
    artist("The Velvet Underground", "Like The Velvet Underground? Recommend similar bands")
    artist("Sonic Youth", "Love Sonic Youth's noise rock? Which other bands are similar?")
    artist("My Bloody Valentine", "Like My Bloody Valentine's shoegaze? Similar bands?")
    artist("Slowdive", "Love Slowdive's dreamy shoegaze? Who else is similar?")
    artist("Cocteau Twins", "Like Cocteau Twins' dream pop? Recommend similar bands")
    artist("Beach House", "Love Beach House's dream pop? Which others are similar?")
    artist("Mazzy Star", "Like Mazzy Star's hazy psych folk? Similar bands?")
    artist("Cigarettes After Sex", "Love Cigarettes After Sex's dreamy slowcore? Who else?")
    artist("The xx", "Like The xx's minimal indie? Who else is similar?")
    artist("Tame Impala", "Love Tame Impala's psychedelic pop? Similar artists?")
    artist("MGMT", "Like MGMT's psychedelic electropop? Similar bands?")
    artist("Vampire Weekend", "Love Vampire Weekend's breezy indie pop? Similar bands?")
    artist("Arcade Fire", "Like Arcade Fire's grand indie rock? Who else is similar?")
    artist("Bon Iver", "Love Bon Iver's ethereal folk? Recommend similar artists")
    artist("Fleet Foxes", "Like Fleet Foxes' harmony folk? Recommend similar bands")
    artist("Sufjan Stevens", "Love Sufjan Stevens' chamber folk? Who else is similar?")
    artist("Iron & Wine", "Like Iron & Wine's soft folk? Recommend similar songwriters")
    artist("The National", "Love The National's brooding indie rock? Who else is similar?")
    artist("Interpol", "Like Interpol's post-punk revival? Recommend similar bands")
    artist("Explosions in the Sky", "Love Explosions in the Sky's post-rock? Similar bands?")
    artist("Sigur Rós", "Like Sigur Rós' ethereal post-rock? Recommend similar bands")
    artist("Mogwai", "Love Mogwai's post-rock? Which other bands are similar?")
    artist("Florence + The Machine", "Like Florence + The Machine's huge vocals? Similar artists?")
    artist("Of Monsters and Men", "Love Of Monsters and Men's folk pop? Who else is similar?")
    artist("Mumford & Sons", "Like Mumford & Sons' folk rock? Recommend similar bands")
    artist("The Lumineers", "Love The Lumineers' folk? Which other bands are similar?")
    artist("Hozier", "Like Hozier's soulful folk? Recommend similar singers")
    artist("alt-J", "Love alt-J's experimental indie pop? Who else is similar?")
    artist("Portishead", "Like Portishead's trip-hop? Recommend similar artists")
    artist("Massive Attack", "Love Massive Attack's dark trip-hop? Who else is similar?")
    artist("Björk", "Like Björk's experimental pop? Recommend similar artists")
    artist("Kate Bush", "Love Kate Bush's art pop? Who else is similar?")
    artist("Enya", "Like Enya's ethereal new age? Recommend similar artists")
    // 欧美 R&B、灵魂乐与嘻哈
    artist("Frank Ocean", "Love Frank Ocean's alternative R&B? Who else is similar?")
    artist("SZA", "Like SZA's R&B? Recommend similar female singers")
    artist("Daniel Caesar", "Love Daniel Caesar's gentle R&B? Which others are similar?")
    artist("H.E.R.", "Like H.E.R.'s R&B? Recommend similar singers")
    artist("D'Angelo", "Love D'Angelo's neo-soul? Who else is similar?")
    artist("Erykah Badu", "Like Erykah Badu's neo-soul? Recommend similar singers")
    artist("Lauryn Hill", "Love Lauryn Hill's rap and soul? Who else is similar?")
    artist("Marvin Gaye", "Like Marvin Gaye's soul? Other singers from that era?")
    artist("Stevie Wonder", "Love Stevie Wonder's soul and funk? Who else is similar?")
    artist("Aretha Franklin", "Like Aretha Franklin's soulful voice? Similar singers?")
    artist("Otis Redding", "Love Otis Redding's Southern soul? Who else is similar?")
    artist("Sam Cooke", "Like Sam Cooke's classic soul? Recommend similar singers")
    artist("Ray Charles", "Love Ray Charles' soul? Which other singers are similar?")
    artist("Al Green", "Like Al Green's gentle soul? Recommend similar singers")
    artist("Amy Winehouse", "Love Amy Winehouse's retro soul? Who else is similar?")
    artist("Alicia Keys", "Like Alicia Keys' piano R&B? Recommend similar singers")
    artist("Anderson .Paak", "Love Anderson .Paak's funky rap? Who else is similar?")
    artist("Kendrick Lamar", "Like Kendrick Lamar's rap? Recommend similar rappers")
    artist("Kanye West", "Love Kanye West's production? Which other rappers are similar?")
    artist("Eminem", "Like Eminem's rapid-fire rap? Recommend similar rappers")
    artist("Drake", "Love Drake's melodic rap? Who else is similar?")
    artist("Tupac", "Like Tupac's West Coast rap? Other rappers from that era?")
    artist("The Notorious B.I.G.", "Love The Notorious B.I.G.'s East Coast rap? Who else?")
    artist("Nas", "Like Nas' storytelling rap? Recommend similar rappers")
    artist("A Tribe Called Quest", "Love A Tribe Called Quest's jazz rap? Who else is similar?")
    artist("J Dilla", "Like J Dilla's beats? Recommend similar producers")
    artist("MF DOOM", "Love MF DOOM's underground rap? Who else is similar?")
    artist("Tyler, the Creator", "Like Tyler, the Creator's style? Recommend similar artists")
    artist("Childish Gambino", "Love Childish Gambino's funk? Who else is similar?")
    artist("Tom Misch", "Like Tom Misch's jazzy funk guitar? Similar artists?")
    artist("Jacob Collier", "Love Jacob Collier's harmonies? Who else is similar?")
    artist("Vulfpeck", "Like Vulfpeck's funk? Recommend other similar bands")
    artist("Snarky Puppy", "Love Snarky Puppy's fusion? Which other bands are similar?")
    artist("Earth, Wind & Fire", "Like Earth, Wind & Fire's funk? Other bands from that era?")
    artist("Prince", "Love Prince's funk pop? Who else is similar?")
    // 电子
    artist("Daft Punk", "Like Daft Punk's French house? Recommend similar duos")
    artist("Aphex Twin", "Love Aphex Twin's experimental electronica? Who else?")
    artist("Boards of Canada", "Like Boards of Canada's nostalgic electronica? Similar artists?")
    artist("Brian Eno", "Love Brian Eno's ambient music? Which others are similar?")
    artist("Jean-Michel Jarre", "Like Jean-Michel Jarre's synth music? Similar artists?")
    artist("Kraftwerk", "Love Kraftwerk's electronic pioneering? Who else is similar?")
    artist("Moby", "Like Moby's electronica? Recommend similar artists")
    artist("The Chemical Brothers", "Love The Chemical Brothers' big beat? Who else is similar?")
    artist("Röyksopp", "Like Röyksopp's Nordic electronica? Recommend similar duos")
    artist("Air", "Love Air's French electronica? Which others are similar?")
    artist("Bonobo", "Like Bonobo's downtempo? Recommend similar producers")
    artist("Tycho", "Love Tycho's warm ambient electronica? Who else is similar?")
    artist("ODESZA", "Like ODESZA's electronica? Recommend similar duos")
    artist("Four Tet", "Love Four Tet's electronica? Which other producers are similar?")
    artist("Burial", "Like Burial's dark electronica? Recommend similar artists")
    artist("Jon Hopkins", "Love Jon Hopkins' electronica? Who else is similar?")
    artist("Nils Frahm", "Like Nils Frahm's piano and electronics? Similar artists?")
    artist("Ólafur Arnalds", "Love Ólafur Arnalds' neo-classical? Who else is similar?")
    artist("Max Richter", "Like Max Richter's neo-classical? Recommend similar composers")
    artist("Avicii", "Love Avicii's EDM? Which other DJs are similar?")
    artist("Disclosure", "Like Disclosure's house? Recommend similar duos")
    artist("Kavinsky", "Love Kavinsky's synthwave? Who else is similar?")
    artist("M83", "Like M83's dreamy electronica? Recommend similar artists")
    artist("Justice", "Love Justice's French electro? Which others are similar?")
    // 爵士
    artist("Miles Davis", "Love Miles Davis? Which other players from his era?")
    artist("John Coltrane", "Like John Coltrane's sax? Recommend similar jazz players")
    artist("Bill Evans", "Love Bill Evans' lyrical piano? Who else is similar?")
    artist("Chet Baker", "Like Chet Baker's languid trumpet and voice? Similar artists?")
    artist("Billie Holiday", "Love Billie Holiday's jazz vocals? Who else is similar?")
    artist("Ella Fitzgerald", "Like Ella Fitzgerald's singing? Recommend similar singers")
    artist("Nina Simone", "Love Nina Simone's soulful jazz? Who else is similar?")
    artist("Sarah Vaughan", "Like Sarah Vaughan's voice? Recommend similar jazz singers")
    artist("Louis Armstrong", "Love Louis Armstrong's early jazz? Who else is similar?")
    artist("Duke Ellington", "Like Duke Ellington's big band jazz? Similar bandleaders?")
    artist("Thelonious Monk", "Love Thelonious Monk's quirky piano? Who else is similar?")
    artist("Dave Brubeck", "Like Dave Brubeck's cool jazz? Recommend similar works")
    artist("Herbie Hancock", "Love Herbie Hancock's fusion? Who else is similar?")
    artist("Keith Jarrett", "Like Keith Jarrett's improvised piano? Similar players?")
    artist("Pat Metheny", "Love Pat Metheny's jazz guitar? Who else is similar?")
    artist("Stan Getz", "Like Stan Getz's sax? Recommend similar players")
    artist("Diana Krall", "Love Diana Krall's piano and vocals? Who else is similar?")
    artist("Esperanza Spalding", "Like Esperanza Spalding's bass and voice? Similar artists?")
    artist("Gregory Porter", "Love Gregory Porter's rich jazz voice? Who else is similar?")
    artist("Kamasi Washington", "Like Kamasi Washington's grand jazz? Similar players?")
    artist("Robert Glasper", "Love Robert Glasper's jazz and hip-hop? Who else is similar?")
    artist("Melody Gardot", "Like Melody Gardot's languid jazz vocals? Similar singers?")
    artist("Chick Corea", "Love Chick Corea's piano? Which other jazz players are similar?")
    artist("Sonny Rollins", "Like Sonny Rollins' sax? Recommend similar players")
    artist("Charlie Parker", "Love Charlie Parker's bebop? Who else is similar?")
    artist("Wes Montgomery", "Like Wes Montgomery's jazz guitar? Similar players?")
    artist("Oscar Peterson", "Love Oscar Peterson's piano? Which other players are similar?")
    // 古典、影视与游戏配乐
    artist("Bach", "Love Bach's counterpoint? Which other composers are similar?")
    artist("Mozart", "Like Mozart's bright works? Recommend similar composers")
    artist("Beethoven", "Love Beethoven's symphonies? Whose works are similar?")
    artist("Chopin", "Like Chopin's piano pieces? Recommend similar composers")
    artist("Debussy", "Love Debussy's impressionism? Which other composers are similar?")
    artist("Ravel", "Like Ravel's orchestral colors? Recommend similar composers")
    artist("Satie", "Love Satie's minimal piano miniatures? Who else is similar?")
    artist("Tchaikovsky", "Like Tchaikovsky's melodies? Recommend similar composers")
    artist("Rachmaninoff", "Love Rachmaninoff's piano concertos? Who else is similar?")
    artist("Mahler", "Like Mahler's vast symphonies? Recommend similar composers")
    artist("Brahms", "Love Brahms? Which other composers are similar?")
    artist("Schubert", "Like Schubert's lieder? Recommend similar composers")
    artist("Vivaldi", "Love Vivaldi's Baroque? Who else is similar?")
    artist("Dvořák", "Like Dvořák's folk flavor? Recommend similar composers")
    artist("Sibelius", "Love Sibelius' Nordic character? Who else is similar?")
    artist("Liszt", "Like Liszt's virtuoso piano? Recommend similar composers")
    artist("Mendelssohn", "Love Mendelssohn? Which other composers are similar?")
    artist("Grieg", "Like Grieg's Nordic melodies? Recommend similar composers")
    artist("Philip Glass", "Love Philip Glass' minimalism? Who else is similar?")
    artist("Steve Reich", "Like Steve Reich's phasing loops? Recommend similar composers")
    artist("Arvo Pärt", "Love Arvo Pärt's holy minimalism? Who else is similar?")
    artist("Ludovico Einaudi", "Like Ludovico Einaudi's piano? Recommend similar composers")
    artist("Hans Zimmer", "Love Hans Zimmer's film scores? Who else is similar?")
    artist("John Williams", "Like John Williams' scores? Recommend similar composers")
    artist("Ennio Morricone", "Love Ennio Morricone's scores? Who else is similar?")
    artist("Thomas Newman", "Like Thomas Newman's delicate scores? Similar composers?")
    artist("Alexandre Desplat", "Love Alexandre Desplat's scores? Who else is similar?")
    artist("Yann Tiersen", "Like Yann Tiersen's piano scores? Recommend similar composers")
    artist("Danny Elfman", "Love Danny Elfman's whimsical scores? Who else is similar?")
    artist("Howard Shore", "Like Howard Shore's epic scores? Recommend similar composers")
    artist("James Horner", "Love James Horner's scores? Who else is similar?")
    artist("Tan Dun", "Like Tan Dun's East-meets-West scores? Similar composers?")
    artist("Shigeru Umebayashi", "Love Shigeru Umebayashi's film music? Who else is similar?")
    // 其他世界音乐
    artist("Cesária Évora", "Like Cesária Évora's Cape Verdean morna? Similar singers?")
    artist("Fela Kuti", "Love Fela Kuti's Afrobeat? Recommend similar artists")
    artist("Ali Farka Touré", "Like Ali Farka Touré's Malian blues? Who else is similar?")
    artist("Youssou N'Dour", "Love Youssou N'Dour's African pop? Recommend similar artists")
    artist("Caetano Veloso", "Like Caetano Veloso's Brazilian music? Similar singers?")
    artist("Gilberto Gil", "Love Gilberto Gil's Brazilian pop? Who else is similar?")
    artist("Jorge Ben Jor", "Like Jorge Ben Jor's samba-funk? Recommend similar artists")
    artist("Antônio Carlos Jobim", "Love Antônio Carlos Jobim's Bossa Nova? Who else is similar?")
    artist("João Gilberto", "Like João Gilberto's hushed Bossa Nova? Similar artists?")
    artist("Bob Marley", "Love Bob Marley's reggae? Which other artists are similar?")
    artist("Ravi Shankar", "Like Ravi Shankar's sitar? Recommend similar Indian music")
    artist("Nusrat Fateh Ali Khan", "Love Nusrat Fateh Ali Khan's qawwali? Who else is similar?")
    artist("Édith Piaf", "Like Édith Piaf's chanson? Recommend similar French singers")
    artist("Serge Gainsbourg", "Love Serge Gainsbourg's French pop? Who else is similar?")
    artist("Françoise Hardy", "Like Françoise Hardy's French folk? Similar female voices?")
    artist("Charles Aznavour", "Love Charles Aznavour's chanson? Who else is similar?")
    artist("Stromae", "Like Stromae's French electropop? Recommend similar singers")
    artist("Zaz", "Love Zaz's French jazz pop? Who else is similar?")
}

/** 听感质感与特色淘歌。 */
private fun listeningPool() = aiWelcomePool(LISTENING) {
    +"Open a music mystery box: 3 great songs in different styles"
    +"Hidden gems with intros that grab you instantly"
    +"Songs good enough to loop all day"
    +"Songs with lyrics that read like poetry"
    +"Underrated singles with stunning arrangements"
    +"Healing tunes that brighten my mood instantly"
    +"Immersive, spacious songs for noise-canceling headphones"
    +"Catchy gems that haven't been overplayed"
    +"Songs with outrageously funky bass lines"
    +"Acoustic solos with great harmonics and bends"
    +"Big songs with a soaring, emotional finale"
    +"Retro songs with that crackly vinyl texture"
    +"Lush a cappella with rich harmonies"
    +"Sharp lyrics that hit modern life's pain points"
    +"Richly layered songs that reveal more each listen"
    +"Songs with goosebump-inducing key changes"
    +"Bass-heavy songs to crank up and feel the drums"
    +"Narrative songs that tell a story"
    +"Underground bands with very distinctive singers"
    +"Misty songs that feel like soaking in warm water"
    +"Slow rock with absurdly long but blissful interludes"
    +"Beautiful vocal songs with almost no synths"
    +"Light songs with dry humor that make me smile"
    +"Audiophile female vocals with crystal-clear recording"
    +"Jazz classics with soul-stirring trumpet or sax solos"
    +"Piano pieces where every note rings crisp and clear"
    +"Long healing songs that build to complete release"
    +"Retro melodies that feel like old film reels"
    +"Songs that hook you in the first three seconds"
    +"Songs with long outros I never want to end"
    +"Rock songs with explosive drummers"
    +"Pop songs with gorgeous piano parts"
    +"Love songs with beautiful string arrangements"
    +"Folk songs with moving harmonica"
    +"Romantic pieces with accordion"
    +"Classic rock with long guitar solos"
    +"Songs with gloriously retro synths"
    +"Jazz where voice and instruments talk to each other"
    +"Live versions better than the studio recordings"
    +"Stunning unplugged versions"
    +"Covers with more flavor than the originals"
    +"The same song in different languages"
    +"Great duets"
    +"Songs in three-four waltz time"
    +"Songs in odd time signatures like 7/8"
    +"Very slow songs where time seems to stretch"
    +"Fast songs that aren't noisy"
    +"Songs with lyrics full of imagery"
    +"Songs with no chorus that still hold up"
    +"Songs with choruses I instantly want to sing"
    +"Records to savor detail with good headphones"
    +"Songs with deep sub-bass to test headphones"
    +"Songs with vocals so close they feel whispered in my ear"
    +"Wide recordings that sound like a concert hall"
    +"Old records with warm mastering"
    +"Songs with Lo-Fi texture from old gear"
    +"Fun tracks with field recordings or samples"
    +"Songs using rain, waves or other nature sounds"
    +"Songs made with toy instruments or odd sounds"
    +"Songs with lovely whistling"
    +"Songs with children's choirs"
    +"Songs mixing rap and strings"
    +"Songs mixing traditional instruments with electronics"
    +"Classical pieces reworked into pop"
    +"New songs that sample classic oldies"
    +"Songs that unfold slowly like a long take in a film"
    +"Epic long pieces with huge emotional swings"
    +"Complete little pieces under two minutes"
    +"Songs over ten minutes that never get boring"
    +"Quiet verses that suddenly explode in the chorus"
    +"Songs that end abruptly and leave an aftertaste"
    +"Songs with really interesting titles"
    +"Simple melodies that stick after one listen"
    +"Sparse songs with lots of space"
    +"Pop songs with sophisticated chord progressions"
    +"Songs where the bassist is the star"
    +"Songs with sexy sax solos"
    +"Pop songs with catchy violin"
    +"Melancholy pieces with low, murmuring cello"
    +"Songs with dreamy harp"
    +"Jazz with vibraphone"
    +"Overwhelming pipe organ pieces"
    +"Gentle little music-box melodies"
    +"Songs with warm Rhodes electric piano"
    +"Retro songs with Wurlitzer electric piano"
    +"Breezy ukulele songs"
    +"Country songs with banjo"
    +"Psychedelic songs with sitar"
    +"Crisp little kalimba pieces"
    +"Songs with infectious hand-drum rhythms"
    +"Songs with retro drum-machine beats"
    +"Songs where voices are used as instruments"
    +"Songs with brilliant falsetto"
    +"Songs sung with a smoky voice"
    +"Rich songs by deep male voices"
    +"Gentle love songs by mezzo-sopranos"
    +"Songs with clean, boyish voices"
    +"Songs with distinctive diction"
    +"Songs that sound great in a regional dialect"
    +"Folk songs with beautiful multi-part harmonies"
    +"Rock songs backed by a full orchestra"
    +"Intimate songs where you can hear the singer breathe"
    +"Retro songs that sound like old radio broadcasts"
    +"Songs that feel like a Wong Kar-wai film"
    +"Music as warm as a Miyazaki film"
    +"Songs that sound like road-movie soundtracks"
    +"Electronic music that sounds like a cyberpunk city"
    +"Folk songs that sound recorded in a forest"
    +"Ambient as deep as the ocean floor"
    +"Post-rock as vast as a starry sky"
    +"Songs that feel like yellowed old photos"
    +"Songs that feel as unreal as a dream"
    +"Songs as refreshing as iced coffee"
    +"Songs as warm as hot cocoa"
    +"Songs that sound expensive"
    +"Rough-sounding Lo-Fi that's still great"
    +"Songs with a surprise twist"
    +"Songs whose intro sounds nothing like the rest"
    +"Classics covered across every generation"
    +"Great songs used in ads"
    +"Classic songs featured in films"
    +"Great songs I've heard in video games"
    +"Songs that would make good ringtones"
    +"Lesser-known records worth hearing start to finish"
    +"An album to listen to from start to finish"
    +"Albums with great covers and great music"
    +"Give me a style I've never heard"
    +"Songs I probably wouldn't pick but should try"
    +"Songs that reveal something new every listen"
    +"Sad melodies with upbeat rhythms"
    +"Upbeat melodies with heartbreaking lyrics"
    +"Songs so quiet you can hear fingers on strings"
    during(AFTERNOON, DUSK) { +"Pieces for watching the sunset from the window" }
    inSeason(WINTER) { during(NOON, AFTERNOON) { +"Cozy melodies for basking in the winter afternoon sun" } }
}

/** 歌单整理与分析：只问工具实际读得到的内容。 */
private fun playlistPool() = aiWelcomePool(PLAYLIST) {
    +"Analyze my playlists. What's my taste like?"
    +"What genres are most of my playlists?"
    +"Roast my combined playlist"
    +"Pick 3 songs from my combined playlist that hold up best"
    +"Is my combined playlist too all over the place?"
    +"Pick upbeat songs from my playlists for a picnic"
    +"Pull the Chinese folk songs from my playlists into a new one"
    +"Pull the classic Cantopop from my playlists into a new playlist"
    +"Pick the best running songs from my playlists"
    +"Make an upbeat playlist for a long drive"
    +"Make an energetic playlist for a big clean"
    +"Show me how the artists in my playlists are spread"
    +"Pick rainy-day songs from my playlists and make a new one"
    +"Pull out the instrumentals and make a zone-out playlist"
    +"Pick my liveliest songs for a good-mood playlist"
    +"Pick songs with great intros from my playlists to share"
    +"Make a slow playlist for walks"
    +"Which songs in my playlists feel most like film scores?"
    +"Which artists show up most in my playlists?"
    +"How many playlists and songs do I have?"
    +"How many songs overlap between two of my playlists?"
    +"Find songs shared by my combined playlist and others"
    +"Pick out the songs that are in both playlists"
    +"Which songs appear in only one playlist?"
    +"Find duplicate songs in a playlist"
    +"Merge two similar playlists into a new one"
    +"Are there empty playlists I can clean up?"
    +"Suggest more interesting names for my playlists"
    +"Split a playlist into a fast one and a slow one"
    +"Pull English songs from my combined playlist into a new one"
    +"Make a Japanese playlist from my songs"
    +"Make a separate Korean playlist from my songs"
    +"Separate instrumentals from vocal songs in my playlist"
    +"Pick out the live versions in my playlists"
    +"Pick driving songs from my playlists"
    +"Pick workout songs from my playlists"
    +"Pick the best alarm song from my playlists"
    +"Make a ten-song best-of from my playlists"
    +"Recommend new songs in the style of my playlists"
    +"Based on my combined playlist, which artists might I like?"
    +"Are there different versions of the same song in my playlists?"
    +"Which directions does my taste lean?"
    +"Compare the styles of two of my playlists"
    +"Describe my playlists in three words"
    +"If my playlists were a movie, what genre would it be?"
    +"Write me a fun listening portrait based on my playlists"
    +"Sort the songs in my playlist by mood"
    +"Pick a few songs from my playlists to share with friends"
    +"Show me the Cantonese songs in my playlists"
    +"How many instrumentals are in my playlists?"
    +"What playlists do I have on each music source?"
    +"Add a few songs from my source playlists to the combined one"
    +"What styles are the playlists I follow?"
    +"Copy one of my source playlists into a local playlist"
    +"How much do my local and source playlists overlap?"
    +"Remove songs from my combined playlist that are in other playlists"
    +"Delete a local playlist I no longer need"
    +"Fix inconsistent artist names in my playlist"
    +"Any typos in song titles or artist names in my playlists?"
    +"Pick the longest songs in my playlist"
    +"Pick the shortest songs in my playlist"
    +"How long is the average song in my playlist?"
    +"Make a commute playlist of about an hour from my songs"
    +"Make a relaxing playlist exactly half an hour long"
    +"Pick cooking songs from my playlists"
    +"Which songs in my playlists are good for a party?"
    +"Pick karaoke songs from my playlists"
    +"Pick a relaxing set from my playlists"
    +"Which bands are in my playlists?"
    +"Find all the songs by female singers in my playlist"
    +"Group the songs in my playlist by artist"
    +"Which artist has the most songs in my combined playlist?"
    +"What styles are in my biggest playlist?"
    +"Sample my biggest playlist. Is it worth trimming?"
    +"Any songs in my playlists that don't fit together?"
    +"Make a birthday playlist from my songs"
    +"Make a playlist of songs with stunning intros"
    +"Make a playlist to give to a friend"
    +"Trim my playlist down to a 20-song essentials version"
    +"Show the overlap between my combined playlist and others"
    +"Find songs common to several playlists and make a Favorites playlist"
    +"What share of my playlists is foreign-language songs?"
    during(EVENING, LATE_NIGHT) {
        +"Make a sleepy playlist for nights I can't sleep"
        +"Gather the slower songs from my playlists into a bedtime playlist"
        +"Make a tipsy playlist for a drink alone late at night"
    }
    during(DAWN, MORNING) { +"Make a gentle playlist for waking up to morning sun" }
    inSeason(AUTUMN) { during(NOON, AFTERNOON) { +"Pick songs for coffee on an autumn afternoon and make a playlist" } }
}

/** 播放、队列、现成歌单与跨音源。 */
private fun playbackPool() = aiWelcomePool(PLAYBACK) {
    +"What's in my play queue right now?"
    +"Who sings the song that's playing?"
    +"Add a few similar songs after the one that's playing"
    +"Play something upbeat next"
    +"Replace the queue with zone-out instrumentals"
    +"Play my combined playlist"
    +"Play one of my own playlists, any one"
    +"Find a ready-made City Pop playlist and play it"
    +"Find a ready-made jazz playlist"
    +"Find a ready-made Chinese folk playlist"
    +"Find a few good post-rock playlists for me to choose"
    +"Find a ready-made running playlist and play it"
    +"Find a ready-made classic rock playlist"
    +"Find a live version of the current song on Bilibili"
    +"Find some good covers on Bilibili"
    +"Find classic concert footage on Bilibili"
    +"Find covers of the song that's playing"
    +"Are there other versions of the current song?"
    +"Queue up other hits by the artist that's playing"
    +"The queue feels messy. Take a look?"
    +"What's coming up next?"
    +"Add a few songs with a similar tempo after this one"
    +"A few more like the one that's playing"
    +"Play some songs, I'm not picky"
    +"Just play something good"
    +"Play me a song, surprise me"
    +"Play a few songs that fit right now"
    +"Play a set of 80s hits"
    +"Play some great film soundtracks"
    +"Add a few relaxing songs to the end of the queue"
    +"Clear the queue and switch to easy jazz"
    +"Play a classic album in full"
    +"Find a ready-made Japanese playlist"
    +"Find a ready-made Korean playlist and play it"
    +"Search another music source for the song that's playing"
    +"Search only the current source for some City Pop"
    +"Find some live versions, but not on Bilibili"
    +"Play some local songs from my phone"
    +"Find an instrumental version of the song that's playing"
    +"Find instrumental arrangements of this song"
    +"Play songs from the same album as the current one"
    +"Make the next song something quieter"
    +"Slip an upbeat song into the queue"
    during(EVENING, LATE_NIGHT) { +"Find a ready-made bedtime playlist" }
    during(DAWN, MORNING) { +"Find a ready-made morning playlist" }
    onWeekend { +"Play a weekend party set" }
}

/** 音乐知识与故事；需要新资料的只在联网开启时出现。 */
private fun knowledgePool() = aiWelcomePool(KNOWLEDGE) {
    +"How did City Pop become popular? Recommend some classics"
    +"How is post-rock different from regular rock? Give examples"
    +"What are the main styles of jazz? One song for each"
    +"What is Bossa Nova? Play me some classics"
    +"Why is shoegaze called shoegaze? Some starter songs"
    +"Tell me the story of trip-hop, then recommend some"
    +"What's the difference between R&B and soul? One of each"
    +"What is Lo-Fi? Recommend some classics"
    +"Tell me about Britpop's golden era, with key songs"
    +"What was Taiwan's campus folk movement? Key songs?"
    +"What are the classics of Cantopop's golden age?"
    +"What is neo-soul? Some starter songs"
    +"How did the blues shape rock? Give some examples"
    +"What is neo-classical music? Some starter works"
    +"How did disco rise and fall? Some classics"
    +"What is math rock? Let me hear some"
    +"What are hip-hop's four elements? Some old-school rap"
    +"What's the difference between house and techno?"
    +"Getting into classical music, where do I start?"
    +"Getting into jazz, which albums first?"
    +"Getting into post-rock, which bands first?"
    +"How are City Pop and vaporwave related?"
    +"What makes a funk groove? Some typical examples"
    +"Why do some songs sound retro? Give examples"
    +"Tell me the story of Motown, with key songs"
    +"What is dream pop? How is it different from shoegaze?"
    +"Explain the synthwave aesthetic, with key songs"
    +"What is ambient music? Some starter tracks"
    +"What are the main styles of Latin music?"
    +"What does reggae's offbeat feel like? Some classics"
    +"How did Chinese rock begin? Some key songs"
    +"What is emo? Recommend some key songs"
    +"Explain the movements of a symphony"
    +"What is a key change? Songs with famous ones"
    +"What is harmony? Songs with beautiful harmonies"
    +"Explain leitmotifs in film scores, with examples"
    +"How did reggaeton start? Some key songs"
    +"What's the difference between salsa and son cubano?"
    +"What is flamenco's compás? Some examples"
    +"Tell me about rock en español, with key songs"
    withWeb {
        +"Any new albums worth hearing lately?"
        +"Look up the story behind the song that's playing"
        +"Which new Mandopop albums this year are well reviewed?"
        +"Any new indie band releases worth watching lately?"
        +"What's new with the artist that's playing?"
        +"Any good new songs lately?"
        +"Look up what the lyrics of the current song are about"
        +"Look up how the current album was recorded"
        +"Any film soundtracks worth hearing lately?"
        +"Any new Japanese songs worth hearing lately?"
        +"Any well-reviewed new Western albums lately?"
        +"Which bands released new albums this year?"
        +"Look up who wrote the song that's playing"
        +"Any good new game soundtracks lately?"
        +"Check the latest album charts and pick a few to hear"
        +"Any new Korean songs worth hearing lately?"
        +"Look up other collaborations by the artist that's playing"
        +"Any new post-rock albums lately?"
        +"Any new jazz records worth hearing lately?"
        +"Any new folk songs lately?"
        +"Any new Latin releases worth hearing lately?"
        +"Which Spanish-language albums this year are well reviewed?"
    }
}
