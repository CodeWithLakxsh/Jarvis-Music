from flask import Flask, request, redirect, jsonify
import jiosaavn
import os
import secrets
from traceback import print_exc
from flask_cors import CORS

app = Flask(__name__)
app.secret_key = os.environ.get("SECRET", secrets.token_hex(32))
CORS(app)

def ensure_list(data):
    if isinstance(data, dict):
        if 'results' in data:
            return data['results']
        elif 'songs' in data:
            return data['songs']
        return [data]
    return data if isinstance(data, list) else []

@app.route('/')
def home():
    return redirect("https://cyberboysumanjay.github.io/JioSaavnAPI/")

@app.route('/song/')
def search():
    lyrics = False
    songdata = True
    query = request.args.get('query')
    lyrics_ = request.args.get('lyrics')
    songdata_ = request.args.get('songdata')
    if lyrics_ and lyrics_.lower() != 'false': lyrics = True
    if songdata_ and songdata_.lower() != 'true': songdata = False
    if query:
        try:
            data = jiosaavn.search_for_song(query, lyrics, songdata)
            return jsonify(ensure_list(data))
        except:
            return jsonify([]) # Return empty list on failure
    else:
        return jsonify([])

@app.route('/song/get/')
def get_song():
    lyrics = False
    song_id = request.args.get('id')
    lyrics_ = request.args.get('lyrics')
    if lyrics_ and lyrics_.lower() != 'false': lyrics = True
    if song_id:
        try:
            resp = jiosaavn.get_song(song_id, lyrics)
            if not resp: return jsonify({"status": False, "error": 'Invalid Song ID!'})
            return jsonify(resp)
        except:
            return jsonify({"status": False, "error": 'Error fetching song'})
    return jsonify({"status": False, "error": 'Song ID is required!'})

@app.route('/playlist/')
def playlist():
    lyrics = False
    query = request.args.get('query')
    lyrics_ = request.args.get('lyrics')
    if lyrics_ and lyrics_.lower() != 'false': lyrics = True
    if query:
        try:
            id = jiosaavn.get_playlist_id(query)
            songs = jiosaavn.get_playlist(id, lyrics)
            return jsonify(ensure_list(songs))
        except:
            return jsonify([])
    return jsonify([])

@app.route('/album/')
def album():
    lyrics = False
    query = request.args.get('query')
    lyrics_ = request.args.get('lyrics')
    if lyrics_ and lyrics_.lower() != 'false': lyrics = True
    if query:
        try:
            id = jiosaavn.get_album_id(query)
            songs = jiosaavn.get_album(id, lyrics)
            return jsonify(ensure_list(songs))
        except:
            return jsonify([])
    return jsonify([])

@app.route('/lyrics/')
def lyrics():
    query = request.args.get('query')
    if query:
        try:
            if 'http' in query and 'saavn' in query:
                id = jiosaavn.get_song_id(query)
                lyrics_data = jiosaavn.get_lyrics(id)
            else:
                lyrics_data = jiosaavn.get_lyrics(query)
            return jsonify({"status": True, "lyrics": lyrics_data})
        except Exception as e:
            return jsonify({"status": False, "error": str(e)})
    return jsonify({"status": False, "error": 'Query is required!'})

@app.route('/result/')
def result():
    lyrics = False
    query = request.args.get('query')
    # Default to 20, or whatever user specifies
    n = int(request.args.get('n', 20))
    lyrics_ = request.args.get('lyrics')
    if lyrics_ and lyrics_.lower() != 'false': lyrics = True

    if not query:
        return jsonify([])

    try:
        data = None
        if 'saavn' not in query:
            data = jiosaavn.search_for_song(query, lyrics, True)
        elif '/song/' in query:
            song_id = jiosaavn.get_song_id(query)
            data = jiosaavn.get_song(song_id, lyrics)
        elif '/album/' in query:
            id = jiosaavn.get_album_id(query)
            data = jiosaavn.get_album(id, lyrics)
        elif '/playlist/' in query or '/featured/' in query:
            id = jiosaavn.get_playlist_id(query)
            data = jiosaavn.get_playlist(id, lyrics)

        # Ensure we always return a list, and slice it to 'n'
        result_list = ensure_list(data)
        return jsonify(result_list[:n])

    except Exception as e:
        print_exc()
        return jsonify([]) # Return empty list instead of crashing

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5100, use_reloader=True, threaded=True)